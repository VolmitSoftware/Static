import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 12000
const UNKNOWN = '58242087-5710-4c11-9205-9a3f75ae18db'
const OFFLINE = '318a6d41-6545-4c92-ac5e-eed1270d7958'
const CALENDAR = ['day', 'week', 'month']
const DURATIONS = ['online_time', 'active_time', 'afk_time']
const RATIOS = ['kill_death_ratio', 'melee_damage_ratio', 'melee_hit_ratio', 'arrow_damage_ratio',
  'arrow_hit_ratio', 'arrow_accuracy', 'votes_per_day']

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function bests(context, target = '') {
  const response = await context.command(`/staticfixture bests ${target}`.trim(), /^Fixture bests requested [a-f0-9-]{36}$/, TIMEOUT)
  const request = response.slice('Fixture bests requested '.length)
  await until(context, async () => {
    const status = await context.command(`/staticfixture bests-ready ${request}`,
      new RegExp(`^Fixture bests (pending|saved|failed) ${request}$`), TIMEOUT)
    context.expect(!status.includes('failed'), 'Fixture best snapshot write failed', { request })
    return status.includes('saved')
  }, 'Fixture best snapshot was not saved')
  const file = path.join(path.dirname(process.env.STATIC_QA_PLUGIN_DIR), 'StaticFixture', 'bests-results', `${request}.json`)
  return JSON.parse(await readFile(file, 'utf8'))
}

function record(context, snapshot, period, statistic) {
  const result = snapshot?.records.find(entry => entry.period === period && entry.statistic === statistic)
  context.expect(Boolean(result), `Missing ${period} best for ${statistic}`, snapshot)
  return result
}

function calendarRecords(context, snapshot) {
  return CALENDAR.map(period => record(context, snapshot, period, 'blocks_broken'))
}

function assertCalendar(context, snapshot, expected) {
  for (const entry of calendarRecords(context, snapshot)) {
    context.expect(entry.value === expected && !entry.recovered && entry.startedAt <= entry.recordedSince
      && entry.recordedSince <= entry.observedAt && entry.observedAt <= entry.endsAt,
    'A calendar best has an incorrect value or source metadata', entry)
  }
}

function fixedRecord(entry) {
  const { inProgress, ...result } = entry
  return result
}

function componentText(component) {
  if (component === undefined || component === null) return ''
  if (typeof component === 'string') {
    if (/^\s*(?:\{|\[|")/.test(component)) {
      try { return componentText(JSON.parse(component)) } catch {}
    }
    return component.replace(/§[0-9a-fk-orx]/gi, '')
  }
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (typeof component !== 'object') return String(component)
  if ('type' in component && 'value' in component) return componentText(component.value)
  return componentText(component.text) + componentText(component.extra)
}

function itemText(item) {
  if (!item) return ''
  const lore = item.components?.find(component => component.type === 'lore')?.data
    ?? item.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [componentText(item.customName), ...lore.map(componentText)].join('\n')
}

async function synchronize(context, window, label) {
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`${label}: inventory synchronization timed out`)), TIMEOUT)
    context.bot._syncWindow(window).then(() => {
      clearTimeout(timeout)
      resolve()
    }, error => {
      clearTimeout(timeout)
      reject(error)
    })
  })
}

async function open(context, command) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  await opened
  context.expect(context.bot.currentWindow?.inventoryStart === 54, `${command}: expected a 54-slot menu`)
}

async function click(context, slot, button = 0) {
  const from = componentText(context.bot.currentWindow?.title)
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(slot, button, 0)
  try {
    await opened
  } catch (error) {
    throw new Error(`Menu transition from ${from}, slot ${slot}, button ${button}: ${error.message}`, { cause: error })
  }
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Menu did not close')
  await synchronize(context, context.bot.inventory, 'Menu close')
}

async function closeButton(context) {
  const closed = context.waitForEvent('windowClose', () => true, TIMEOUT)
  await context.bot.clickWindow(53, 0, 0)
  await closed
  context.expect(context.bot.currentWindow === null, 'The menu close button did not close the inventory')
  await synchronize(context, context.bot.inventory, 'Menu close button')
}

function assertMenu(context, scope) {
  const window = context.bot.currentWindow
  context.expect(componentText(window?.title).includes('Personal bests'), 'Personal bests title is missing')
  context.expect(itemText(window.slots[45]).includes(`Scope: ${scope}`), 'Personal bests scope is incorrect', { scope })
  const coverage = itemText(window.slots[50])
  context.expect(coverage.includes('Earlier bests use retained history only')
    && coverage.includes('Saved bests remain when source history is pruned.'), 'Personal best coverage is incomplete', { coverage })
}

function configuration(sessions = 2) {
  return `[tracking]\ntrackCreative = false\nautosaveSeconds = 10\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[history]\ntimeZone = "UTC"\nafkSeconds = 5\nretainedDays = 1\nretainedWeeks = 1\nretainedMonths = 1\nsessionsPerPlayer = ${sessions}\n`
}

async function reload(context, file, sessions) {
  const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(file, configuration(sessions))
  await applied
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'Statistics flush failed')
    return response.includes('complete')
  }, 'Statistics flush did not finish')
}

async function latestSample(file, since) {
  let source
  try {
    source = await readFile(file, 'utf8')
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
  const complete = source.slice(0, source.lastIndexOf('\n')).split('\n').filter(Boolean)
  for (let index = complete.length - 1; index >= 0; index--) {
    const value = JSON.parse(complete[index])
    if (value.at >= since) return value
  }
  return null
}

async function permission(context, node, enabled) {
  await context.command(`/staticfixture permission ${node} ${enabled}`, new RegExp(`Fixture permission ${node} ${enabled}`), TIMEOUT)
}

async function restart(context, directory, baselineFile) {
  const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
  const storageFile = path.join(directory, 'storage', 'players.json')
  await context.step('restart preserves calendar best values, dates, and tracking coverage', async () => {
    const restored = await bests(context)
    context.expect(restored.trackingSince === baseline.trackingSince, 'Restart changed personal best tracking coverage')
    context.expect(JSON.stringify(calendarRecords(context, restored).map(fixedRecord)) === JSON.stringify(baseline.calendar),
      'Restart changed the recorded calendar bests', { baseline: baseline.calendar, restored })
    const storage = JSON.parse(await readFile(storageFile, 'utf8'))
    const player = storage.players.find(entry => entry.uuid === restored.uuid)
    const previous = player.history.sessions.find(entry => entry['started-at'] === baseline.session.startedAt)
    context.expect(Boolean(previous), 'The saved first session is missing')
    const online = record(context, restored, 'session', 'online_time')
    context.expect(online.startedAt === previous['started-at'] && !online.inProgress
      && online.endsAt === previous['checkpoint-at'] && online.value === previous['eligible-millis']
      && online.value >= baseline.session.value, 'Restart credited downtime or changed the previous session best', { online, previous })
    context.report.restartedBests = restored
  })
  await context.step('pruning the old session keeps its independently saved personal best', async () => {
    const before = record(context, await bests(context), 'session', 'online_time')
    await reload(context, path.join(directory, 'config.toml'), 1)
    await flush(context)
    const storage = JSON.parse(await readFile(storageFile, 'utf8'))
    const player = storage.players.find(entry => entry.uuid === baseline.uuid)
    context.expect(player.history.sessions.length === 1
      && player.history.sessions.every(entry => entry['started-at'] !== baseline.session.startedAt),
    'Retention did not prune the prior session source', player.history.sessions)
    const after = record(context, await bests(context), 'session', 'online_time')
    context.expect(JSON.stringify(after) === JSON.stringify(before), 'Pruning discarded or rewrote the saved session best', { before, after })
    context.report.prunedSessionBest = after
  })
  await context.step('restored session bests remain available in the command menu', async () => {
    await open(context, `/static bests ${context.bot.username} session 1`)
    assertMenu(context, 'Session')
    const text = context.bot.currentWindow.slots.slice(0, 45).map(itemText).join('\n')
    context.expect(text.includes('Time online') && text.includes('Session started:')
      && text.includes('The source window or session is closed.'), 'Restored best lacks its session source details', { text })
    await close(context)
  })
}

export default {
  name: 'static-bests',
  description: 'Verify personal best values, calendar and session sources, menus, permissions, retention, and restart persistence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    context.expect(Boolean(directory), 'STATIC_QA_PLUGIN_DIR is required')
    const fixtureDirectory = path.join(path.dirname(directory), 'StaticFixture')
    const baselineFile = path.join(fixtureDirectory, 'bests-baseline.json')
    const phase = process.env.STATIC_QA_MODE ?? 'seed'
    context.report.staticMode = phase
    if (phase === 'restart') {
      await restart(context, directory, baselineFile)
      return
    }
    let initial
    let afterBreak
    let session
    await context.step('new profiles expose only observed eligible records and unknown UUIDs remain absent', async () => {
      context.expect(await bests(context, UNKNOWN) === null, 'Looking up unknown bests created a profile')
      initial = await bests(context)
      context.expect(initial.trackingSince > 0 && initial.historyZone === 'UTC', 'New best tracking lacks UTC coverage', initial)
      context.expect(!initial.records.some(entry => entry.statistic === 'blocks_broken'), 'Fresh test profile already has block records')
      context.expect(initial.records.every(entry => entry.value > 0 && !RATIOS.includes(entry.statistic)
        && (entry.period !== 'session' || DURATIONS.includes(entry.statistic))), 'An unsupported or unobserved record was exposed', initial)
    })
    await reload(context, path.join(directory, 'config.toml'), 2)
    await context.step('one real survival block break sets day, week, and month personal bests', async () => {
      if (context.bot.game.gameMode !== 'survival') {
        context.bot.chat('/gamemode survival')
        await until(context, () => context.bot.game.gameMode === 'survival', 'Survival mode was not applied')
      }
      await context.command('/execute if entity @s[gamemode=survival] run tellraw @s "Best survival confirmed"', /Best survival confirmed/, TIMEOUT)
      await context.command('/give @s minecraft:netherite_pickaxe 1', /Gave /, TIMEOUT)
      await synchronize(context, context.bot.inventory, 'Pickaxe grant')
      const pickaxe = context.bot.inventory.items().find(item => item.name === 'netherite_pickaxe')
      context.expect(Boolean(pickaxe), 'Pickaxe was not granted')
      await context.bot.equip(pickaxe, 'hand')
      await synchronize(context, context.bot.inventory, 'Pickaxe equip')
      await context.command('/execute if items entity @s weapon.mainhand minecraft:netherite_pickaxe run tellraw @s "Best tool confirmed"', /Best tool confirmed/, TIMEOUT)
      const position = context.bot.entity.position.floored().offset(2, 0, 0)
      context.bot.chat(`/setblock ${position.x} ${position.y} ${position.z} minecraft:stone`)
      await until(context, () => context.bot.blockAt(position)?.name === 'stone', 'Stone test block did not arrive')
      await context.command(`/execute if block ${position.x} ${position.y} ${position.z} minecraft:stone run tellraw @s "Best block ready"`, /Best block ready/, TIMEOUT)
      await context.bot.dig(context.bot.blockAt(position), true)
      await until(context, () => context.bot.blockAt(position)?.name === 'air', 'The actual test block was not broken')
      let blockAcknowledgment
      await until(context, async () => {
        blockAcknowledgment = await context.command(`/execute if block ${position.x} ${position.y} ${position.z} minecraft:air`, /Test (?:passed|failed)/, TIMEOUT)
        return blockAcknowledgment.includes('Test passed')
      }, 'The server did not confirm that the single physical block break removed the stone')
      await until(context, async () => {
        afterBreak = await bests(context)
        const entries = CALENDAR.map(period => afterBreak?.records.find(entry => entry.period === period && entry.statistic === 'blocks_broken'))
        context.expect(entries.every(entry => !entry || entry.value <= 1), 'The single physical break was counted more than once', { entries })
        return entries.every(entry => entry?.value === 1)
      }, 'The server-confirmed physical break did not produce all three calendar bests')
      assertCalendar(context, afterBreak, 1)
      context.report.actualBreak = { position, blockAcknowledgment, survivalConfirmed: true, toolConfirmed: true, records: calendarRecords(context, afterBreak) }
    })
    await context.step('larger totals update the same source and zero increments leave record dates unchanged', async () => {
      await context.command('/staticfixture add blocks_broken 4', /Fixture added blocks_broken 4/, TIMEOUT)
      const improved = await bests(context)
      assertCalendar(context, improved, 5)
      for (const entry of calendarRecords(context, improved)) {
        const previous = record(context, afterBreak, entry.period, entry.statistic)
        context.expect(entry.startedAt === previous.startedAt && entry.recordedSince === previous.recordedSince,
          'Increasing a current bucket changed its source identity', { previous, entry })
      }
      await context.command('/staticfixture add blocks_broken 0', /Fixture added blocks_broken 0/, TIMEOUT)
      const unchanged = await bests(context)
      context.expect(JSON.stringify(calendarRecords(context, unchanged)) === JSON.stringify(calendarRecords(context, improved)),
        'A zero increment rewrote a personal best')
    })
    await context.step('passive session time produces online, active, and AFK bests without command polling', async () => {
      const began = Date.now()
      await context.command('/staticfixture observe', /Fixture activity observation started/, TIMEOUT)
      let sample
      await until(context, async () => {
        sample = await latestSample(path.join(fixtureDirectory, 'activity-samples.jsonl'), began)
        return sample && sample.at >= began + 9000 && sample.afk >= 3000
      }, 'The passive session never accumulated active and AFK durations')
      const snapshot = await bests(context)
      session = record(context, snapshot, 'session', 'online_time')
      const active = record(context, snapshot, 'session', 'active_time')
      const afk = record(context, snapshot, 'session', 'afk_time')
      const observed = sample.sessions.find(entry => entry.reason === 'OPEN')
      context.expect(session.inProgress && session.endsAt === 0 && session.startedAt === observed.startedAt
        && session.value >= observed.eligible && session.value <= observed.eligible + 2500,
      'Session best is not based on the current eligible session', { session, observed })
      context.expect(active.value > 0 && afk.value >= 3000 && session.value === active.value + afk.value,
        'Session duration bests do not split recorded online time consistently', { session, active, afk })
      context.report.passiveSession = { sample, records: [session, active, afk] }
    })
    await context.step('profile right-click opens bests and all scope, refresh, and back controls work', async () => {
      await open(context, '/stats')
      context.expect(itemText(context.bot.currentWindow.slots[50]).includes('Right-click for recorded personal bests.'),
        'The profile omitted its personal bests entry')
      await click(context, 50, 1)
      assertMenu(context, 'Day')
      const blockText = context.bot.currentWindow.slots.slice(0, 45).map(itemText).find(text => text.startsWith('Blocks broken'))
      context.expect(blockText?.includes('Record value observed:') && blockText.includes('Partial coverage:')
        && blockText.includes('The source window or session is ongoing;'), 'Calendar best lore omits date or partial coverage', { blockText })
      for (const scope of ['Week', 'Month', 'Session', 'Day']) {
        await click(context, 45)
        assertMenu(context, scope)
      }
      await click(context, 49)
      assertMenu(context, 'Day')
      await click(context, 51)
      context.expect(componentText(context.bot.currentWindow.title).includes('Static'), 'Back control did not return to the profile')
      await close(context)
    })
    await context.step('offline profiles have calendar records without invented session records', async () => {
      await context.command(`/staticfixture add-uuid ${OFFLINE} blocks_broken 7`, /Fixture UUID added/, TIMEOUT)
      const offline = await bests(context, OFFLINE)
      assertCalendar(context, offline, 7)
      context.expect(!offline.records.some(entry => entry.period === 'session'), 'Offline increments fabricated session bests')
      await open(context, `/static bests ${OFFLINE} session 1`)
      assertMenu(context, 'Session')
      context.expect(context.bot.currentWindow.slots.slice(0, 45).map(itemText).join('\n')
        .includes('No positive personal bests have been recorded for this scope.'), 'Empty session bests have no explanation')
      await closeButton(context)
      context.report.offlineBests = offline
    })
    await context.step('personal-best permissions reject own and other-player menus without opening an inventory', async () => {
      await permission(context, 'static.bests', false)
      await context.command('/static bests', /You do not have permission to do that\./, TIMEOUT)
      context.expect(context.bot.currentWindow === null, 'Own bests opened despite denied permission')
      await permission(context, 'static.bests', true)
      await permission(context, 'static.bests.others', false)
      await context.command(`/static bests ${OFFLINE}`, /You do not have permission to do that\./, TIMEOUT)
      context.expect(context.bot.currentWindow === null, 'Other-player bests opened despite denied permission')
      await permission(context, 'static.bests.others', true)
      await open(context, `/static bests ${context.bot.username} week 1`)
      assertMenu(context, 'Week')
      await close(context)
    })
    await context.step('record persistence captures fixed values and source dates for restart comparison', async () => {
      const snapshot = await bests(context)
      assertCalendar(context, snapshot, 5)
      context.expect(snapshot.trackingSince === initial.trackingSince, 'Configuration or menu reads reset tracking coverage')
      await flush(context)
      await mkdir(fixtureDirectory, { recursive: true })
      const baseline = { uuid: snapshot.uuid, trackingSince: snapshot.trackingSince,
        calendar: calendarRecords(context, snapshot).map(fixedRecord), session: record(context, snapshot, 'session', 'online_time') }
      await writeFile(baselineFile, JSON.stringify(baseline, null, 2))
      context.report.savedBests = baseline
    })
  }
}
