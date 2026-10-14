import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 12000

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function sample(context, probeFile) {
  const source = await readFile(probeFile, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return null
    throw error
  })
  if (!source) return null
  const end = source.lastIndexOf('\n')
  if (end < 0) return null
  const start = source.lastIndexOf('\n', end - 1) + 1
  const value = JSON.parse(source.slice(start, end))
  context.expect(value.uuid === context.report.bot.uuid, 'The passive sampler observed another profile')
  context.expect(value.online === value.active + value.afk, 'Online time differs from active plus AFK time', value)
  for (const session of value.sessions) {
    context.expect(session.eligible === session.active + session.afk, 'A session has inconsistent time totals', session)
  }
  const captured = context.report.staticActivitySamples
  if (captured.length > 0) {
    const previous = captured.at(-1)
    context.expect(value.online >= previous.online && value.active >= previous.active && value.afk >= previous.afk,
      'An accounting transition reduced an accumulated time counter', { previous, value })
  }
  if (captured.length === 0 || captured.at(-1).sequence !== value.sequence) captured.push(value)
  return value
}

async function freshSample(context, probeFile, predicate = () => true) {
  let result
  await until(context, async () => {
    result = await sample(context, probeFile)
    return result && predicate(result)
  }, 'The expected passive activity sample did not arrive')
  return result
}

async function setMode(context, mode) {
  await context.command(`/gamemode ${mode}`, new RegExp(mode, 'i'), TIMEOUT)
  await until(context, () => context.bot.game.gameMode === mode, `The player did not enter ${mode}`)
}

async function placeholder(context, key) {
  const response = await context.command(`/papi parse me QABEGIN%static_${key}%QAEND`, /^QABEGIN.*QAEND$/, TIMEOUT)
  const value = response.match(/^QABEGIN(.*?)QAEND$/)[1]
  context.expect(!value.includes('%static_'), 'Static placeholder was not resolved', { key, value })
  return value
}

async function period(context, selected, statistic) {
  const response = await context.command(`/staticfixture period ${selected} ${statistic}`, /^Fixture period /, TIMEOUT)
  const match = response.match(/^Fixture period (\w+) (\w+) ([0-9.e+-]+) (\S+) (\d+) (\d+) (\d+) (\S+)$/)
  context.expect(match, 'The fixture returned an invalid period snapshot', { response })
  const value = { period: match[1], statistic: match[2], value: Number(match[3]), id: match[4],
    startedAt: Number(match[5]), endsAt: Number(match[6]), recordedSince: Number(match[7]), zone: match[8] }
  context.expect(Number.isFinite(value.value) && value.recordedSince > 0,
    'A recorded period has invalid totals or unknown coverage', value)
  return value
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'The statistics flush failed')
    return response.includes('complete')
  }, 'The statistics flush did not finish')
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

async function openProfile(context, selected) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(`/stats ${context.bot.username} ${selected}`)
  await opened
  await until(context, () => context.bot.currentWindow?.slots[51], 'The period control did not arrive')
}

async function nextPeriod(context, label) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(51, 0, 0)
  await opened
  await until(context, () => itemText(context.bot.currentWindow?.slots[51]).includes(`Period: ${label}`),
    `The profile did not switch to ${label}`)
}

async function capture(context, command, patterns) {
  const messages = []
  const listener = message => messages.push(message)
  context.bot.on('messagestr', listener)
  try {
    context.bot.chat(command)
    await until(context, () => patterns.every(pattern => messages.some(message => pattern.test(message))),
      `Expected messages did not arrive for ${command}`)
    return messages
  } finally {
    context.bot.removeListener('messagestr', listener)
  }
}

async function openHistoryEditor(context) {
  const rootOpened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat('/static config')
  await rootOpened
  await until(context, () => itemText(context.bot.currentWindow?.slots[41]).includes('History and activity'),
    'The configuration editor omitted its History and activity category')
  const categoryOpened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(41, 0, 0)
  await categoryOpened
  await until(context, () => itemText(context.bot.currentWindow?.slots[0]).includes('Calendar time zone'),
    'The History settings did not arrive')
}

async function requireSetting(context, directory, slot, key, value) {
  const text = itemText(context.bot.currentWindow?.slots[slot])
  context.expect(text.split('\n').includes(`Current: ${value}`), 'The editor shows an unexpected setting value', { key, value, text })
  const source = await readFile(path.join(directory, 'config.toml'), 'utf8')
  const serialized = typeof value === 'string' ? `"${value}"` : String(value)
  context.expect(new RegExp(`^${key}\\s*=\\s*${serialized}\\s*$`, 'm').test(source),
    'The editor did not save the displayed value to canonical TOML', { key, value })
}

async function adjustHistorySetting(context, directory, slot, key, value, button) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(slot, button, 0)
  await opened
  await until(context, () => itemText(context.bot.currentWindow?.slots[slot]).split('\n').includes(`Current: ${value}`),
    `The ${key} editor did not show the saved value ${value}`)
  await requireSetting(context, directory, slot, key, value)
}

async function historyEditor(context, directory, probeFile) {
  await context.step('History editor exposes six settings and persists timezone and reversible retention edits', async () => {
    await openHistoryEditor(context)
    const expected = [
      ['Calendar time zone', 'UTC'], ['Seconds without input before AFK', 5],
      ['Retained daily windows', 35], ['Retained weekly windows', 12],
      ['Retained monthly windows', 12], ['Retained sessions per player', 20]
    ]
    context.report.staticHistoryEditor = expected.map(([label, value], slot) => {
      const text = itemText(context.bot.currentWindow.slots[slot])
      context.expect(text.startsWith(label) && text.split('\n').includes(`Current: ${value}`),
        'A History setting is missing or has an unexpected initial value', { slot, label, value, text })
      return { slot, label, value }
    })
    const prompt = context.waitForMessage(/Type a new value for Calendar time zone.*in chat/, TIMEOUT)
    await context.bot.clickWindow(0, 0, 0)
    await prompt
    const reopened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
    context.bot.chat('UTC')
    await reopened
    await until(context, () => itemText(context.bot.currentWindow?.slots[0]).includes('Current: UTC'),
      'Submitting the current timezone did not reopen the editor')
    await requireSetting(context, directory, 0, 'timeZone', 'UTC')
    for (const [slot, key, value] of [[2, 'retainedDays', 35], [3, 'retainedWeeks', 12],
      [4, 'retainedMonths', 12], [5, 'sessionsPerPlayer', 20]]) {
      await adjustHistorySetting(context, directory, slot, key, value + 1, 0)
      await adjustHistorySetting(context, directory, slot, key, value, 1)
    }
  })
  await context.step('AFK editor applies its ten-second increment to runtime accounting and restores the setting', async () => {
    await adjustHistorySetting(context, directory, 1, 'afkSeconds', 15, 0)
    context.bot.closeWindow(context.bot.currentWindow)
    const observedAt = Date.now()
    await context.command('/staticfixture observe', /Fixture activity observation started/, TIMEOUT)
    const before = await freshSample(context, probeFile, value => value.at >= observedAt)
    const later = await freshSample(context, probeFile, value => value.at >= before.at + 6500)
    context.expect(later.afk === before.afk && later.active >= before.active + 6000,
      'The saved fifteen-second AFK threshold was not applied to runtime accounting', { before, later })
    context.report.staticEditorActiveMillis = later.active - before.active
    await openHistoryEditor(context)
    await adjustHistorySetting(context, directory, 1, 'afkSeconds', 5, 1)
    context.bot.closeWindow(context.bot.currentWindow)
  })
}

export default {
  name: 'static-activity',
  description: 'Check passive AFK accounting, movement, eligibility, calendar totals, sessions and restart persistence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory && ['seed', 'restart', 'editor'].includes(mode), 'Set the isolated data folder and seed, restart, or editor mode')
    const probeFile = path.join(directory, '..', 'StaticFixture', 'activity-samples.jsonl')
    const baselineFile = path.join(directory, '..', 'StaticFixture', 'activity-baseline.json')
    const baseline = mode === 'restart' ? JSON.parse(await readFile(baselineFile, 'utf8')) : null
    context.report.staticMode = mode
    context.report.staticActivitySamples = []
    if (mode === 'editor') {
      await historyEditor(context, directory, probeFile)
      return
    }
    await context.command('/staticfixture observe', /Fixture activity observation started/, TIMEOUT)
    const started = await freshSample(context, probeFile, value => value.at >= Date.now() - 1000)

    if (mode === 'restart') {
      await context.step('restart opens a new session without attributing downtime to playtime', async () => {
        const current = started.sessions.find(session => session.reason === 'OPEN')
        const previous = started.sessions.find(session => session.id === baseline.sessionId)
        context.expect(current && previous && current.id !== previous.id, 'Restart did not preserve and separate the sessions', started)
        context.expect(previous.endedAt > 0 && previous.endedAt <= current.startedAt
          && ['QUIT', 'SHUTDOWN'].includes(previous.reason), 'The previous session was not closed cleanly', previous)
        context.expect(current.eligible < 10000, 'The new session includes pre-join time', current)
        const sessionTime = started.sessions.reduce((sum, session) => sum + session.eligible, 0)
        context.expect(Math.abs(started.online - sessionTime) <= 250, 'Lifetime time includes a gap outside recorded sessions', started)
        context.report.staticDowntimeMillis = current.startedAt - previous.endedAt
      })
      await context.step('raw calendar totals and ratios survive restart', async () => {
        for (const selected of ['day', 'week', 'month']) {
          const saved = baseline.periods.find(value => value.period === selected)
          const restored = await period(context, selected, 'blocks_broken')
          context.expect(restored.id === saved.id && restored.value === 7, 'Restart changed the current calendar totals', restored)
          context.expect(await placeholder(context, `${selected}_kill_death_ratio`) === '2', 'Restart changed the calendar ratio')
        }
      })
      return
    }

    await context.step('an idle player gains AFK time while online equals active plus AFK', async () => {
      const idle = await freshSample(context, probeFile, value => value.afk >= started.afk + 800)
      const later = await freshSample(context, probeFile, value => value.at >= idle.at + 800)
      context.expect(later.active === idle.active && later.afk > idle.afk,
        'Passive observation reset the idle timer or classified idle time as active', { idle, later })
    })

    await context.step('hot reloading the idle threshold checkpoints the old policy without reclassifying recorded time', async () => {
      const configFile = path.join(directory, 'config.toml')
      const source = await readFile(configFile, 'utf8')
      context.expect(/^afkSeconds\s*=\s*5\s*$/m.test(source), 'The bounded test requires a five-second AFK threshold')
      const before = await sample(context, probeFile)
      try {
        await writeFile(configFile, source.replace(/^afkSeconds\s*=.*$/m, 'afkSeconds = 30'))
        const longer = await freshSample(context, probeFile, value => value.active >= before.active + 400)
        context.expect(longer.afk >= before.afk, 'Increasing the threshold rewrote previously recorded AFK time', { before, longer })
        await writeFile(configFile, source)
        await freshSample(context, probeFile, value => value.afk >= longer.afk + 400)
      } finally {
        await writeFile(configFile, source)
      }
    })

    await context.step('real player movement ends AFK and resumes active time', async () => {
      const before = await sample(context, probeFile)
      const origin = context.bot.entity.position.clone()
      try {
        context.bot.setControlState('forward', true)
        await until(context, () => context.bot.entity.position.distanceTo(origin) >= 2, 'The player did not move two blocks')
      } finally {
        context.bot.clearControlStates()
      }
      const resumed = await freshSample(context, probeFile, value => value.active >= before.active + 200)
      const later = await freshSample(context, probeFile, value => value.at >= resumed.at + 800)
      context.expect(later.active > resumed.active && later.afk === resumed.afk,
        'Movement did not establish an active interval', { resumed, later })
      context.report.staticMovementDistance = context.bot.entity.position.distanceTo(origin)
    })

    await context.step('excluded spectator time pauses all three counters and survival resumes active time', async () => {
      await setMode(context, 'spectator')
      const changedAt = Date.now()
      const paused = await freshSample(context, probeFile, value => value.at >= changedAt)
      const later = await freshSample(context, probeFile, value => value.at >= paused.at + 1000)
      context.expect(later.online === paused.online && later.active === paused.active && later.afk === paused.afk,
        'Ineligible game mode accumulated time', { paused, later })
      await setMode(context, 'survival')
      const resumed = await freshSample(context, probeFile, value => value.active >= later.active + 400)
      context.expect(resumed.afk === later.afk, 'Eligibility resume inherited an idle interval', { later, resumed })
    })

    await context.step('public increments appear in each current calendar period with derived ratios', async () => {
      for (const [statistic, amount] of [['blocks_broken', 7], ['pvp_kills', 20], ['pvp_deaths', 10]]) {
        await context.command(`/staticfixture add ${statistic} ${amount}`, /Fixture added/, TIMEOUT)
      }
      const periods = []
      for (const selected of ['day', 'week', 'month']) {
        const blocks = await period(context, selected, 'blocks_broken')
        const ratio = await period(context, selected, 'kill_death_ratio')
        context.expect(blocks.value === 7 && ratio.value === 2, 'A current period lost base totals or derived an incorrect ratio', { blocks, ratio })
        context.expect(blocks.startedAt <= blocks.recordedSince && blocks.recordedSince < blocks.endsAt,
          'Recorded coverage is outside the selected period', blocks)
        context.expect(await placeholder(context, `${selected}_blocks_broken`) === '7', 'Calendar placeholder disagrees with the public API')
        context.expect(await placeholder(context, `${selected}_kill_death_ratio`) === '2', 'Calendar ratio placeholder is incorrect')
        context.expect(await placeholder(context, `${selected}_recorded_since`) === String(blocks.recordedSince),
          'Calendar coverage placeholder disagrees with the selected snapshot')
        periods.push(blocks)
      }
      context.report.staticPeriods = periods
    })

    await context.step('a timezone alias hot reload is rejected while the recorded calendar and working settings remain usable', async () => {
      const configFile = path.join(directory, 'config.toml')
      const source = await readFile(configFile, 'utf8')
      const logBefore = await readFile(context.report.server.logPath, 'utf8')
      const expected = 'History time zone is chosen at startup and cannot change while Static is running'
      context.expect(/^timeZone\s*=/m.test(source), 'The calendar timezone setting is missing')
      try {
        await writeFile(configFile, source.replace(/^timeZone\s*=.*$/m, 'timeZone = "Z"'))
        await until(context, async () => (await readFile(context.report.server.logPath, 'utf8')).slice(logBefore.length).includes(expected),
          'The invalid live timezone change was not rejected')
        const retained = await period(context, 'day', 'blocks_broken')
        const original = context.report.staticPeriods.find(value => value.period === 'day')
        context.expect(retained.zone === original.zone && retained.id === original.id && retained.value === 7,
          'The rejected timezone changed calendar totals or boundaries', { original, retained })
        context.report.staticExpectedRejections = [expected]
      } finally {
        await writeFile(configFile, source)
      }
    })

    await context.step('profile period selection exposes the selected totals, activity counters, and calendar coverage', async () => {
      await openProfile(context, 'day')
      const window = context.bot.currentWindow
      context.expect(itemText(window.slots[51]).includes('Period: Today'), 'The command did not select today')
      const coverage = itemText(window.slots[50])
      context.expect(coverage.includes('Recorded totals · Today') && coverage.includes('Window:')
        && coverage.includes('Calendar time zone:') && coverage.includes('Partial coverage:'),
        'The current-period profile did not explain its calendar window and recorded coverage', { coverage })
      const items = window.slots.slice(0, 45).map(itemText)
      context.expect(items.some(text => /^Blocks broken\b/.test(text) && /Blocks broken: 7\b/.test(text)),
        'The profile shows a different period total')
      context.expect(items.some(text => /^Active time\b/.test(text)) && items.some(text => /^AFK time\b/.test(text)),
        'The profile omitted the activity counters')
      for (const label of ['This week', 'This month', 'Lifetime']) await nextPeriod(context, label)
      context.bot.closeWindow(context.bot.currentWindow)
      await until(context, () => context.bot.currentWindow === null, 'The profile did not close')
    })

    await context.step('period rankings and cached qualified placeholders select the same calendar totals', async () => {
      const messages = []
      for (const [selected, label] of [['day', 'Today'], ['week', 'This week'], ['month', 'This month']]) {
        const command = `/static top kill_death_ratio 1 ${selected}`
        messages.push({ period: selected, messages: await capture(context, command,
          [new RegExp(label), new RegExp(`#1 ${context.bot.username}: 2\\.00`), /Qualified: 1\/1 profiles/]) })
        context.expect(await placeholder(context, `${selected}_rank_kill_death_ratio`) === '1', 'The calendar personal rank is incorrect')
        context.expect(await placeholder(context, `${selected}_qualification_kill_death_ratio_qualified`) === 'true',
          'The calendar ranking lost its exact qualifying boundary')
      }
      context.report.staticPeriodRankings = messages
    })

    await context.step('session command reports the current connection with online, active, and AFK totals', async () => {
      context.report.staticSessionMessages = await capture(context, '/static sessions',
        [/In progress/, /Online:.*Active:.*AFK:/])
    })

    await context.step('eligible session totals and calendar counters are saved for the restart check', async () => {
      await flush(context)
      const latest = await freshSample(context, probeFile, value => value.at > started.at)
      const current = latest.sessions.find(session => session.reason === 'OPEN')
      context.expect(current && current.active > 0 && current.afk > 0, 'The current session did not record both activity states', current)
      await writeFile(baselineFile, JSON.stringify({ sessionId: current.id, at: latest.at, periods: context.report.staticPeriods }, null, 2))
    })
  }
}
