import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 15000
const LEGACY = 'bf444daa-93c3-3ca4-956c-15343613d5a4'
const API_ONLY = '9bc2b7c1-c3d4-47a8-a114-243106060001'

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

function configuration(windows = 3, sessions = 4) {
  return `[tracking]\nautosaveSeconds = 10\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[history]\ntimeZone = "UTC"\nafkSeconds = 5\nretainedDays = ${windows}\nretainedWeeks = ${windows}\nretainedMonths = ${windows}\nsessionsPerPlayer = ${sessions}\n`
}

async function reload(context, windows = 3, sessions = 4) {
  const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(path.join(process.env.STATIC_QA_PLUGIN_DIR, 'config.toml'), configuration(windows, sessions))
  await applied
}

async function capture(context, players, period = 'day', offset = 0, cached = false) {
  const result = await context.command(`/staticfixture participation ${period} ${offset} ${cached ? 'cached' : 'fresh'} ${players.join(' ')}`,
    /^Fixture participation requested [a-f0-9-]{36}$/, TIMEOUT)
  const request = result.slice('Fixture participation requested '.length)
  await until(context, async () => {
    const status = await context.command(`/staticfixture participation-ready ${request}`,
      new RegExp(`^Fixture participation (pending|saved|failed) ${request}$`), TIMEOUT)
    context.expect(!status.includes('failed'), 'Participation fixture request failed', { request })
    return status.includes('saved')
  }, 'Participation fixture request did not finish')
  const file = path.join(path.dirname(process.env.STATIC_QA_PLUGIN_DIR), 'StaticFixture', 'participation-results', `${request}.json`)
  return JSON.parse(await readFile(file, 'utf8'))
}

async function expected(context, players, metrics, period = 'day', offset = 0) {
  let result
  await until(context, async () => {
    result = await capture(context, players, period, offset)
    return Object.entries(metrics).every(([key, value]) => result?.[key] === value)
  }, `Participation did not reach ${JSON.stringify(metrics)} for ${period}/${offset}`)
  context.expect(result.historyZone === 'UTC' && result.window.period === period
    && result.window.startedAt < result.window.endsAt, 'Participation window metadata is incorrect', result)
  context.expect(result.uniquePlayers === result.newPlayers + result.returningPlayers + result.unknownCohortPlayers,
    'Participation cohorts do not partition the retained participants', result)
  return result
}

function assertMedian(context, snapshot) {
  const completed = snapshot.profiles.flatMap(profile => profile.sessions)
    .filter(session => session.endReason === 'QUIT' && session.endedAt >= snapshot.window.startedAt
      && session.endedAt < snapshot.window.endsAt)
  const durations = completed.map(session => session.eligibleMillis).sort((left, right) => left - right)
  const middle = Math.floor(durations.length / 2)
  const median = durations.length === 0 ? null : durations.length % 2 ? durations[middle]
    : durations[middle - 1] / 2 + durations[middle] / 2
  context.expect(snapshot.completedSessions === completed.length && snapshot.medianSessionMillis === median,
    'Summary median differs from independently sampled retained normal-quit sessions', { snapshot, completed, median })
  for (const session of completed) {
    context.expect(session.eligibleMillis === session.activeMillis + session.afkMillis
      && session.eligibleMillis <= session.checkpointAt - session.startedAt,
    'A completed eligible duration is inconsistent', session)
  }
  return { samples: completed, median }
}

async function chat(context, command, patterns) {
  const messages = []
  const received = message => messages.push(String(message))
  context.bot.on('messagestr', received)
  try {
    context.bot.chat(command)
    await until(context, () => patterns.every(pattern => messages.some(message => pattern.test(message))),
      `Participation response did not contain ${patterns.join(', ')}`)
    return messages
  } finally {
    context.bot.removeListener('messagestr', received)
  }
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const result = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!result.includes('failed'), 'Statistics flush failed')
    return result.includes('complete')
  }, 'Statistics flush did not finish')
}

async function restart(context, baseline) {
  const players = [baseline.visitor, LEGACY, API_ONLY]
  await context.step('restart retains connection cohorts and completed quit samples without crediting downtime', async () => {
    const restored = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, returningPlayers: 0,
      unknownCohortPlayers: 1, completedSessions: 3, openSessions: 1, interruptedSessions: 0, shutdownSessions: 0 })
    const independent = assertMedian(context, restored)
    context.expect(process.env.STATIC_QA_SEED_STORAGE, 'STATIC_QA_SEED_STORAGE is required for restart comparison')
    const stored = JSON.parse((await readFile(process.env.STATIC_QA_SEED_STORAGE, 'utf8')).replace(/^\uFEFF/, ''))
    await flush(context)
    const current = JSON.parse((await readFile(path.join(process.env.STATIC_QA_PLUGIN_DIR, 'storage', 'players.json'), 'utf8')).replace(/^\uFEFF/, ''))
    const priorProvenance = stored.players.find(player => player.uuid === baseline.visitor)?.history?.participation
    const restoredProvenance = current.players.find(player => player.uuid === baseline.visitor)?.history?.participation
    context.expect(priorProvenance?.['discarded-through'] > 0 && restoredProvenance
      && ['discarded-through', 'first-connection-at', 'first-connection-known', 'retention-known']
        .every(key => priorProvenance[key] === restoredProvenance[key]),
    'Restart lost the visitor pruning marker or connection provenance', { priorProvenance, restoredProvenance })
    context.report.pruningPersistence = { visitor: baseline.visitor, priorProvenance, restoredProvenance }
    for (const profile of restored.profiles) {
      const previous = stored.players.find(player => player.uuid === profile.uuid)?.history?.sessions ?? []
      for (const session of previous) {
        const actual = profile.sessions.find(entry => entry.id === session.id)
        context.expect(actual && actual.endReason === session['end-reason'] && actual.endedAt === session['ended-at']
          && actual.checkpointAt === session['checkpoint-at'] && actual.eligibleMillis === session['eligible-millis'],
        'Restart altered a saved completed session or added offline time', { session, actual })
      }
    }
    context.report.restartParticipation = restored
    context.report.restartMedian = independent
  })
  await context.step('retention limitations and operator summary remain explicit after restart', async () => {
    const snapshot = await capture(context, players)
    context.expect(snapshot.retentionLimited, 'Session pruning evidence disappeared across restart', snapshot)
    context.report.restartMessages = await chat(context, '/static participation',
      [/Server participation.*Day.*offset 0/, /Unique players with retained sessions: 3/,
        /First observed in window: 2.*Returning: 0.*Unknown history: 1/,
        /Quit sessions ending in window: 3.*Median eligible duration:/, /Session retention can limit this window/])
  })
}

export default {
  name: 'static-participation',
  description: 'Verify real connection cohorts, retained quit-session medians, API-only exclusion, permissions, window bounds, retention changes, and restart.',
  async run(context) {
    context.expect(process.env.STATIC_QA_PLUGIN_DIR, 'STATIC_QA_PLUGIN_DIR is required')
    context.expect(context.report.bot.uuid === context.bot.player?.uuid, 'Joined primary identity is unavailable')
    const fixture = path.join(path.dirname(process.env.STATIC_QA_PLUGIN_DIR), 'StaticFixture')
    const baselineFile = path.join(fixture, 'participation-baseline.json')
    const phase = process.env.STATIC_QA_MODE ?? 'seed'
    context.report.staticMode = phase
    if (phase === 'restart') {
      await restart(context, JSON.parse(await readFile(baselineFile, 'utf8')))
      return
    }
    await reload(context)
    let visitor
    let legacy
    let players = [LEGACY, API_ONLY]
    await context.step('raw legacy and API-only counters do not fabricate participants or median samples', async () => {
      await context.command(`/staticfixture add-uuid ${API_ONLY} joins 500`, /Fixture UUID added/, TIMEOUT)
      await context.command(`/staticfixture add-uuid ${API_ONLY} online_time 86400000`, /Fixture UUID added/, TIMEOUT)
      const snapshot = await expected(context, players, { uniquePlayers: 1, newPlayers: 1, returningPlayers: 0,
        unknownCohortPlayers: 0, completedSessions: 0, openSessions: 1, medianSessionMillis: null })
      context.expect(snapshot.profiles.filter(profile => profile.uuid !== context.report.bot.uuid)
        .every(profile => profile.sessions.length === 0), 'Raw offline counters fabricated actual sessions')
      assertMedian(context, snapshot)
      context.report.rawCounterExclusion = { seededLegacy: LEGACY, apiOnly: API_ONLY, apiJoins: 500, apiOnlineMillis: 86400000, snapshot }
    })
    await context.step('a real ordinary player joins the new cohort and cannot use the operator summary', async () => {
      visitor = await context.connectActor('StaticVisitor')
      players = [visitor.bot.player.uuid, LEGACY, API_ONLY]
      await visitor.command('/static participation', /You do not have permission to do that/, TIMEOUT)
      const snapshot = await expected(context, players, { uniquePlayers: 2, newPlayers: 2, returningPlayers: 0,
        unknownCohortPlayers: 0, completedSessions: 0, openSessions: 2 })
      assertMedian(context, snapshot)
      context.report.newParticipant = { username: visitor.bot.username, uuid: visitor.bot.player.uuid, protocol: visitor.bot.version, snapshot }
    })
    await context.step('a real join with missing stored connection provenance remains an unknown cohort', async () => {
      legacy = await context.connectActor('StaticLegacy')
      context.expect(legacy.bot.player.uuid === LEGACY, 'The controlled legacy identity differs from the connected player')
      const snapshot = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, returningPlayers: 0,
        unknownCohortPlayers: 1, completedSessions: 0, openSessions: 3 })
      assertMedian(context, snapshot)
      context.report.unknownParticipant = { username: legacy.bot.username, uuid: LEGACY, protocol: legacy.bot.version, snapshot }
    })
    await context.step('a real quit and same-window reconnect creates one median sample without changing cohorts', async () => {
      await until(context, async () => {
        const observed = await capture(context, players)
        const session = observed.profiles.find(profile => profile.uuid === visitor.bot.player.uuid)?.sessions[0]
        return session?.eligibleMillis >= 1000
      }, 'The visitor did not accrue an eligible session duration')
      const former = visitor
      visitor = await former.reconnectAfter(() => former.bot.quit('Static participation normal quit'), { timeoutMs: 20000 })
      const snapshot = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, returningPlayers: 0,
        unknownCohortPlayers: 1, completedSessions: 1, openSessions: 3 })
      const independent = assertMedian(context, snapshot)
      context.expect(independent.samples[0].eligibleMillis >= 1000, 'Normal quit lost its recorded eligible duration')
      context.report.realQuitMedian = { snapshot, independent }
    })
    await context.step('day, week, month, and cached summaries expose the same retained real sessions', async () => {
      for (const period of ['day', 'week', 'month']) {
        const snapshot = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, returningPlayers: 0,
          unknownCohortPlayers: 1, completedSessions: 1, openSessions: 3 }, period)
        assertMedian(context, snapshot)
        const cached = await capture(context, players, period, 0, true)
        context.expect(cached?.refreshedAt === snapshot.refreshedAt && cached.uniquePlayers === snapshot.uniquePlayers
          && cached.medianSessionMillis === snapshot.medianSessionMillis, 'Cached participation differs from completed capture')
      }
      const empty = await expected(context, players, { uniquePlayers: 0, completedSessions: 0, medianSessionMillis: null }, 'day', 1)
      context.report.previousWindow = empty
    })
    await context.step('operator chat identifies the window, retained cohort, median population, and coverage limits', async () => {
      context.report.summaryMessages = await chat(context, '/static participation day 0',
        [/Server participation.*Day.*offset 0/, /Unique players with retained sessions: 3/,
          /First observed in window: 2.*Returning: 0.*Unknown history: 1/,
          /Quit sessions ending in window: 1.*Median eligible duration:/,
          /Median uses each retained quit session's full eligible duration/,
          /Retained session history only; older activity may be missing/, /Calendar time zone: UTC/])
      await chat(context, '/static participation day 1', [/No retained sessions overlap this window; this does not prove there were no players/])
    })
    await context.step('permissions and invalid calendar windows are rejected', async () => {
      await context.command('/staticfixture permission static.participation false', /Fixture permission static.participation false/, TIMEOUT)
      await context.command('/static participation', /You do not have permission to do that/, TIMEOUT)
      await context.command('/staticfixture permission static.participation true', /Fixture permission static.participation true/, TIMEOUT)
      await context.command('/static participation lifetime', /Unknown participation period: lifetime/, TIMEOUT)
      await context.command('/static participation day -1', /Offset must be between 0 and 2/, TIMEOUT)
      await context.command('/static participation day 3', /Offset must be between 0 and 2/, TIMEOUT)
    })
    await context.step('hot reloaded window retention changes command bounds immediately', async () => {
      await reload(context, 1)
      await context.command('/static participation day 1', /Offset must be between 0 and 0/, TIMEOUT)
      await reload(context)
      await chat(context, '/static participation day 1', [/No retained sessions overlap this window/])
    })
    await context.step('session pruning removes the median sample and labels the retained lower bound', async () => {
      await reload(context, 3, 1)
      const snapshot = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, unknownCohortPlayers: 1,
        completedSessions: 0, medianSessionMillis: null, openSessions: 3, retentionLimited: true })
      assertMedian(context, snapshot)
      context.expect(snapshot.profiles.every(profile => profile.sessions.length <= 1), 'Session retention did not prune old sources')
      await chat(context, '/static participation', [/Session retention can limit this window/, /No retained completed quit sessions ended in this window/])
      context.report.prunedParticipation = snapshot
      await reload(context)
    })
    await context.step('real participant identities and retained session sources are saved for restart', async () => {
      await flush(context)
      const snapshot = await expected(context, players, { uniquePlayers: 3, newPlayers: 2, unknownCohortPlayers: 1,
        completedSessions: 0, medianSessionMillis: null, openSessions: 3 })
      const baseline = { primary: context.report.bot.uuid, visitor: visitor.bot.player.uuid, legacy: LEGACY, apiOnly: API_ONLY,
        snapshot, actorCleanup: 'Managed harness actors disconnect normally after this phase.' }
      await mkdir(fixture, { recursive: true })
      await writeFile(baselineFile, JSON.stringify(baseline, null, 2))
      context.report.savedParticipation = baseline
    })
  }
}
