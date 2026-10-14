import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'

const TIMEOUT = 12000
const OBSERVER = 'StaticWitness'
const NOTICE = /Milestone complete:/

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(75)
  }
  context.expect(false, message)
}

function event(bot, name, timeout = TIMEOUT) {
  return new Promise((resolve, reject) => {
    const received = (...values) => { clearTimeout(timer); resolve(values) }
    const timer = setTimeout(() => {
      bot.removeListener(name, received)
      reject(new Error(`Timed out waiting for ${name}`))
    }, timeout)
    bot.once(name, received)
  })
}

function text(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') {
    if (/^\s*(?:\{|\[|")/.test(value)) {
      try { return text(JSON.parse(value)) } catch {}
    }
    return value.replace(/§[0-9a-fk-orx]/gi, '')
  }
  if (Array.isArray(value)) return value.map(text).join('')
  if (typeof value !== 'object') return String(value)
  if ('type' in value && 'value' in value) return text(value.value)
  return text(value.text) + text(value.extra)
}

function itemText(item) {
  if (!item) return ''
  const lore = item.components?.find(component => component.type === 'lore')?.data
    ?? item.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [text(item.customName), ...lore.map(text)].join('\n')
}

async function open(context, command = '/static milestones') {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  await opened
  await until(context, () => context.bot.currentWindow?.slots[49], 'Inventory controls did not arrive')
  context.expect(context.bot.currentWindow.inventoryStart === 54, 'Expected a 54-slot inventory')
  return context.bot.currentWindow
}

async function clickOpen(context, slot) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(slot, 0, 0)
  await opened
  await until(context, () => context.bot.currentWindow?.slots[49], 'Replacement inventory did not arrive')
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Inventory did not close')
}

async function snapshot(context, target = '') {
  const response = await context.command(`/staticfixture milestones ${target}`.trim(), /^Fixture milestones \{/, TIMEOUT)
  return JSON.parse(response.slice('Fixture milestones '.length))
}

async function add(context, statistic, amount, target = '') {
  await context.command(`/staticfixture add ${statistic} ${amount} ${target}`.trim(), /Fixture added/, TIMEOUT)
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'Statistics flush failed')
    return response.includes('complete')
  }, 'Statistics flush did not complete')
}

function completion(profile, id) {
  return profile.completions.find(value => value.id === id)
}

async function events(file) {
  const source = await readFile(file, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return ''
    throw error
  })
  return source.slice(0, source.lastIndexOf('\n') + 1).split('\n').filter(Boolean).map(line => JSON.parse(line))
}

function configuration(settings) {
  return `[tracking]\nautosaveSeconds = 60\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\nnotifyOperators = true\n[milestones]\nenabled = ${settings.enabled}\nnotifyPlayers = ${settings.notifyPlayers}\n[milestones.thresholds]\n`
    + Object.entries(settings.thresholds).map(([key, values]) => `${key} = [${values.join(', ')}]`).join('\n') + '\n'
}

async function reload(context, configFile, settings) {
  const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(configFile, configuration(settings))
  await applied
}

async function permission(context, node, allowed) {
  await context.command(`/staticfixture permission ${node} ${allowed}`, new RegExp(`Fixture permission ${node} ${allowed}`), TIMEOUT)
}

export default {
  name: 'static-milestones',
  description: 'Check durable cosmetic milestones, private delivery, live edits, permissions, offline awards and restart idempotence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const packageFile = process.env.STATIC_QA_MINEFLAYER_PACKAGE
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory && packageFile && ['seed', 'restart'].includes(mode), 'Set isolated data, harness package and seed/restart mode')
    const configFile = path.join(directory, 'config.toml')
    const eventFile = path.join(directory, '..', 'StaticFixture', 'milestone-events.jsonl')
    const baselineFile = path.join(directory, '..', 'StaticFixture', 'milestone-baseline.json')
    const mineflayer = createRequire(packageFile)('mineflayer')
    const ownNotices = []
    const witnessNotices = []
    const ownListener = message => { if (NOTICE.test(message)) ownNotices.push({ message, at: Date.now() }) }
    context.bot.on('messagestr', ownListener)
    let witness
    let expectedEnd = false
    let failure
    const healthy = () => context.expect(!failure, 'Witness client failed', { failure: String(failure) })
    const connectWitness = async () => {
      expectedEnd = false
      witness = mineflayer.createBot({ host: context.server.host, port: context.server.port,
        username: OBSERVER, auth: 'offline', version: context.bot.version })
      witness.on('messagestr', message => { if (NOTICE.test(message)) witnessNotices.push({ message, at: Date.now() }) })
      witness.on('error', error => { failure = error })
      witness.on('kicked', reason => { failure = new Error(`Witness kicked: ${JSON.stringify(reason)}`) })
      witness.on('end', reason => { if (!expectedEnd) failure = new Error(`Witness disconnected: ${reason}`) })
      await event(witness, 'spawn', 20000)
      healthy()
    }
    const disconnectWitness = async () => {
      if (witness && !witness._client.ended) {
        expectedEnd = true
        const ended = event(witness, 'end', 5000)
        witness.quit('Static milestone check reconnect')
        await ended
      }
    }
    const recordedEvents = async () => {
      const captured = await events(eventFile)
      for (const entry of captured) {
        const player = entry.stored.players.find(value => value.uuid === entry.uuid)
        const stored = player?.milestones.find(value => value.statistic === entry.completion.statistic
          && value.threshold === entry.completion.threshold)
        context.expect(stored && stored['completed-at'] === entry.completion.completedAt
          && stored['observed-value'] === entry.completion.observedValue && stored.historical === false,
        'Completion callback preceded its matching durable record', entry.completion)
      }
      const keys = captured.map(entry => `${entry.uuid}/${entry.completion.id}`)
      context.expect(new Set(keys).size === keys.length, 'A completion callback was delivered twice', { keys })
      context.report.staticMilestoneEvents = captured.map(({ at, uuid, sessionId, completion }) => ({ at, uuid, sessionId, completion, persistedBeforeCallback: true }))
      return captured
    }
    const expectNoticeCount = async count => {
      await until(context, () => ownNotices.length === count, 'The expected private milestone notice did not arrive')
      context.expect(witnessNotices.length === 0, 'The witness received another player milestone notice', witnessNotices)
      healthy()
    }
    context.report.staticMode = mode
    try {
      await connectWitness()
      context.report.staticMilestoneWitness = { username: witness.username, uuid: witness.player.uuid, protocol: witness.version }
      if (mode === 'restart') {
        const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
        await context.step('restart preserves completion identity and dates without replaying private messages', async () => {
          const own = await snapshot(context)
          const other = await snapshot(context, OBSERVER)
          context.expect(JSON.stringify(own.completions) === JSON.stringify(baseline.own.completions), 'Restart changed earned badges')
          context.expect(JSON.stringify(other.completions) === JSON.stringify(baseline.other.completions), 'Restart changed offline-earned badges')
          await flush(context)
          await context.sleep(600)
          context.expect((await recordedEvents()).length === baseline.eventCount, 'Restart replayed completion callbacks')
          context.expect(!context.report.messages.some(entry => NOTICE.test(entry.message)) && witnessNotices.length === 0,
            'Restart or login replayed a completion notice')
        })
        await context.step('restored badges and current targets remain accessible from the profile and command', async () => {
          await open(context, '/stats')
          context.expect(itemText(context.bot.currentWindow.slots[52]).includes('Milestones and badges'), 'Profile milestone entry is missing')
          await clickOpen(context, 52)
          const items = context.bot.currentWindow.slots.slice(0, 27).map(itemText)
          context.expect(items.some(value => value.includes('Blocks broken · 5') && value.includes('Complete · recorded')),
            'The persisted badge is absent from the milestone inventory')
          await clickOpen(context, 49)
          await close(context)
          await open(context, `/static milestones ${OBSERVER}`)
          context.expect(context.bot.currentWindow.slots.slice(0, 27).map(itemText)
            .some(value => value.includes('Blocks broken · 5') && value.includes('Complete · recorded')), 'Offline completion is missing')
          await close(context)
        })
        return
      }

      const settings = { enabled: true, notifyPlayers: true,
        thresholds: { blocks_broken: [5, 10], blocks_placed: [3], mob_kills: [2] } }
      await context.step('profile navigation and pending progress show configured cosmetic targets', async () => {
        const own = await snapshot(context)
        context.expect(own.progress.length === 4 && own.completions.length === 0, 'A fresh player has unexpected milestone data', own)
        await open(context, '/stats')
        context.expect(itemText(context.bot.currentWindow.slots[52]).includes('Milestones and badges'), 'Profile milestone entry is missing')
        await clickOpen(context, 52)
        const items = context.bot.currentWindow.slots.slice(0, 27).map(itemText)
        context.expect(items.some(value => value.includes('Blocks broken · 5') && value.includes('Progress: 0 / 5')
          && value.includes('In progress')), 'Pending milestone lore is incorrect', { items })
        await clickOpen(context, 48)
        context.expect(itemText(context.bot.currentWindow.slots[52]).includes('Milestones and badges'), 'Back navigation did not return to profile')
        await close(context)
      })
      await context.step('progress below the threshold does not award or notify', async () => {
        await add(context, 'blocks_broken', 4)
        await flush(context)
        const own = await snapshot(context)
        context.expect(own.completions.length === 0 && own.progress.find(value => value.id === 'blocks_broken:5')?.value === 4,
          'The below-threshold milestone state is incorrect', own)
        await open(context)
        context.expect(context.bot.currentWindow.slots.slice(0, 27).map(itemText)
          .some(value => value.includes('Progress: 4 / 5') && value.includes('In progress')), 'Updated progress did not appear')
        await close(context)
        context.expect((await recordedEvents()).length === 0 && ownNotices.length === 0, 'A below-threshold award was emitted')
      })
      await context.step('a real block break completes the exact threshold after durable save with a private notice', async () => {
        const position = context.bot.entity.position.offset(2, 0, 0).floored()
        await context.command('/give @s minecraft:diamond_pickaxe', /given|gave/i, TIMEOUT)
        await until(context, () => context.bot.inventory.items().some(item => item.name === 'diamond_pickaxe'), 'Pickaxe did not arrive')
        await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'diamond_pickaxe'), 'hand')
        await context.command(`/setblock ${position.x} ${position.y} ${position.z} minecraft:stone`, /changed|set the block/i, TIMEOUT)
        await until(context, () => context.bot.blockAt(position)?.name === 'stone', 'Test stone did not arrive')
        await context.bot.dig(context.bot.blockAt(position), true)
        await flush(context)
        await expectNoticeCount(1)
        const own = await snapshot(context)
        const award = completion(own, 'blocks_broken:5')
        context.expect(award && !award.historical && award.observedValue === 5, 'The exact live threshold award is incorrect', own)
        context.expect(own.progress.find(value => value.id === 'blocks_broken:5').value === 5, 'The real break was not counted exactly once')
        context.expect((await recordedEvents()).length === 1, 'The durable callback is missing')
      })
      await context.step('a later tier awards once and repeated flushes and inventory refreshes do not duplicate it', async () => {
        await add(context, 'blocks_broken', 8)
        await flush(context)
        await expectNoticeCount(2)
        context.expect(completion(await snapshot(context), 'blocks_broken:10')?.observedValue === 13, 'The jumped threshold was not awarded')
        await open(context)
        await clickOpen(context, 49)
        await clickOpen(context, 49)
        await close(context)
        await flush(context)
        context.expect((await recordedEvents()).length === 2 && ownNotices.length === 2, 'A milestone was replayed')
      })
      await context.step('new historical thresholds are silent and removed targets retain their original badge', async () => {
        const original = completion(await snapshot(context), 'blocks_broken:5')
        settings.thresholds.blocks_broken = [3, 5, 10]
        await reload(context, configFile, settings)
        context.expect(completion(await snapshot(context), 'blocks_broken:3')?.historical, 'Existing totals did not produce silent historical completion')
        settings.thresholds.blocks_broken = [3, 10]
        await reload(context, configFile, settings)
        const archived = await snapshot(context)
        context.expect(!archived.progress.some(value => value.id === 'blocks_broken:5')
          && completion(archived, 'blocks_broken:5').completedAt === original.completedAt, 'Removing a target changed its earned badge')
        await open(context)
        context.expect(context.bot.currentWindow.slots.slice(0, 27).map(itemText)
          .some(value => value.includes('Blocks broken · 5') && value.includes('Target removed; the badge is retained.')), 'Archived badge lore is missing')
        await close(context)
        settings.thresholds.blocks_broken = [3, 5, 10]
        await reload(context, configFile, settings)
        await flush(context)
        context.expect(completion(await snapshot(context), 'blocks_broken:5').completedAt === original.completedAt,
          'Re-adding the target reset its completion date')
        context.expect((await recordedEvents()).length === 2 && ownNotices.length === 2, 'Historical or re-added target emitted a notice')
      })
      await context.step('disabled milestones retain raw statistics and resume with silent catch-up', async () => {
        settings.enabled = false
        await reload(context, configFile, settings)
        await add(context, 'blocks_placed', 3)
        const disabled = await snapshot(context)
        context.expect(disabled.progress.length === 0 && !completion(disabled, 'blocks_placed:3'), 'Disabled milestone tracking created a completion')
        settings.enabled = true
        await reload(context, configFile, settings)
        const enabled = await snapshot(context)
        context.expect(completion(enabled, 'blocks_placed:3')?.historical
          && enabled.progress.find(value => value.id === 'blocks_placed:3')?.value === 3, 'Re-enable lost raw stats or historical catch-up')
        await flush(context)
        context.expect((await recordedEvents()).length === 2 && ownNotices.length === 2, 'Re-enable replayed historical progress')
      })
      await context.step('milestone configuration controls save notification policy and a typed threshold ladder', async () => {
        await open(context, '/static config')
        context.expect(itemText(context.bot.currentWindow.slots[43]).includes('Milestones'), 'Milestones config category is missing')
        await clickOpen(context, 43)
        context.expect(itemText(context.bot.currentWindow.slots[0]).includes('Track cosmetic milestones')
          && itemText(context.bot.currentWindow.slots[1]).includes('Private completion messages'), 'Milestone toggles are missing')
        await clickOpen(context, 1)
        context.expect(/^notifyPlayers\s*=\s*false\s*$/m.test(await readFile(configFile, 'utf8')), 'Notification toggle did not persist')
        const prompt = context.waitForMessage(/Type a new value for .*Blocks broken.*in chat/, TIMEOUT)
        await context.bot.clickWindow(2, 0, 0)
        await prompt
        const reopened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
        context.bot.chat('3, 5, 10, 15')
        await reopened
        await close(context)
        context.expect((await snapshot(context)).progress.some(value => value.id === 'blocks_broken:15'), 'Typed threshold ladder was not installed')
        await add(context, 'mob_kills', 2)
        await flush(context)
        await context.sleep(250)
        context.expect(completion(await snapshot(context), 'mob_kills:2')?.historical === false, 'Muted notifications suppressed the completion itself')
        context.expect((await recordedEvents()).length === 3 && ownNotices.length === 2, 'Muted completion policy failed')
        await open(context, '/static config')
        await clickOpen(context, 43)
        await clickOpen(context, 1)
        await close(context)
        context.expect(/^notifyPlayers\s*=\s*true\s*$/m.test(await readFile(configFile, 'utf8')), 'Notification toggle was not restored')
        settings.thresholds.blocks_broken = [3, 5, 10, 15]
      })
      await context.step('milestone permissions gate command, profile entry, other profiles and private notices', async () => {
        await permission(context, 'static.milestones', false)
        await context.command('/static milestones', /permission/i, TIMEOUT)
        await open(context, '/stats')
        context.expect(!itemText(context.bot.currentWindow.slots[52]).includes('Milestones and badges'), 'Denied milestone entry is still available')
        await close(context)
        await add(context, 'blocks_broken', 2)
        await flush(context)
        await context.sleep(250)
        context.expect(completion(await snapshot(context), 'blocks_broken:15') && (await recordedEvents()).length === 4,
          'View permission incorrectly prevented earning a badge')
        context.expect(ownNotices.length === 2, 'Denied player received a milestone notification')
        await permission(context, 'static.milestones', true)
        await permission(context, 'static.milestones.others', false)
        await context.command(`/static milestones ${OBSERVER}`, /permission/i, TIMEOUT)
        await permission(context, 'static.milestones.others', true)
        const witnessOpened = event(witness, 'windowOpen')
        witness.chat('/static milestones')
        await witnessOpened
        context.expect(witness.currentWindow.inventoryStart === 54, 'Default nonoperator milestone access failed')
        witness.closeWindow(witness.currentWindow)
      })
      await context.step('offline API increments award durably without broadcast or later login replay', async () => {
        const witnessId = witness.player.uuid
        await disconnectWitness()
        await until(context, () => !context.bot.players[OBSERVER], 'Witness has not left the server')
        await add(context, 'blocks_broken', 5, OBSERVER)
        await flush(context)
        const offline = await snapshot(context, OBSERVER)
        context.expect(offline.uuid === witnessId && offline.completions.length === 2
          && offline.completions.every(value => !value.historical), 'Offline live increments did not preserve two earned thresholds', offline)
        const captured = await recordedEvents()
        context.expect(captured.length === 6 && captured.filter(value => value.uuid === witnessId).every(value => value.sessionId === null)
          && ownNotices.length === 2, 'Offline completion was broadcast or attached to a live session')
        await connectWitness()
        await context.sleep(600)
        context.expect(witnessNotices.length === 0 && ownNotices.length === 2, 'Login replayed offline or permission-muted notices')
      })
      await context.step('maintenance alone checkpoints active time and publishes a durable private milestone', async () => {
        await disconnectWitness()
        await until(context, () => !context.bot.players[OBSERVER], 'Witness has not left the server')
        const response = await context.command('/staticfixture value active_time', /Fixture value active_time ([0-9.e+-]+)/, TIMEOUT)
        const current = Number(response.match(/Fixture value active_time ([0-9.e+-]+)/)[1])
        const threshold = Math.ceil(current / 1000) * 1000 + 3500
        settings.thresholds.active_time = [threshold]
        await reload(context, configFile, settings)
        const observedAt = Date.now()
        await until(context, async () => (await events(eventFile)).some(value => value.uuid === context.report.bot.uuid
          && value.completion.id === `active_time:${threshold}`), 'Passive maintenance did not publish the active-time milestone')
        await expectNoticeCount(3)
        const captured = await recordedEvents()
        const earned = captured.find(value => value.uuid === context.report.bot.uuid && value.completion.id === `active_time:${threshold}`)
        context.expect(!earned.completion.historical && earned.at > observedAt && earned.sessionId,
          'The passive time award was historical or lacked its active session', earned.completion)
        context.report.staticPassiveMilestone = { observedAt, awardedAt: earned.at, threshold,
          elapsedMillis: earned.at - observedAt, APIQueriesDuringWait: 0 }
        delete settings.thresholds.active_time
        await reload(context, configFile, settings)
        await connectWitness()
      })
      await context.step('saved completion identities and observer records form the restart baseline', async () => {
        await flush(context)
        const own = await snapshot(context)
        const other = await snapshot(context, OBSERVER)
        const captured = await recordedEvents()
        context.expect(own.completions.length === 7 && captured.length === 7, 'Final badge totals differ from the scenario')
        await writeFile(baselineFile, JSON.stringify({ own, other, eventCount: captured.length }, null, 2))
        healthy()
      })
    } finally {
      context.report.staticMilestoneNotices = { own: ownNotices, witness: witnessNotices }
      context.bot.removeListener('messagestr', ownListener)
      await disconnectWitness()
      healthy()
    }
  }
}
