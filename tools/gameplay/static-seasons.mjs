import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 12000
const PEER = '00000000-0000-0000-0000-000000000092'

async function until(context, predicate, message, timeout = TIMEOUT) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function profile(context, id) {
  const response = await context.command(`/staticfixture season ${id}`, /^Fixture season \{/, TIMEOUT)
  return JSON.parse(response.slice('Fixture season '.length))
}

async function ranking(context, id) {
  await context.command(`/staticfixture season-request ${id}`, /Fixture season requested/, TIMEOUT)
  let result
  await until(context, async () => {
    const response = await context.command(`/staticfixture season-result ${id}`, /^Fixture season result /, TIMEOUT)
    if (response.endsWith('pending')) return false
    result = JSON.parse(response.slice('Fixture season result '.length))
    return true
  }, 'Season ranking did not complete')
  return result
}

function config(events, creative = false) {
  return `[tracking]\nautosaveSeconds = 10\ntrackCreative = ${creative}\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[seasons]\n`
    + events.map(event => `[[seasons.events]]\nid = "${event.id}"\nname = "${event.name}"\nstartsAt = "${event.startsAt}"\nendsAt = "${event.endsAt}"\nstatistic = "${event.statistic}"\nminimumSample = 0.0\ntrackCreative = true\ntrackSpectator = false\nafkSeconds = 5\nexcludedWorlds = []\n`).join('')
}

async function reload(context, file, events, rejected = false) {
  const feedback = context.waitForMessage(rejected ? /could not|failed|rejected|previous settings/i : /Applied configuration and language file changes/, TIMEOUT)
  await writeFile(file, config(events))
  await feedback
}

async function callbacks(file) {
  const raw = await readFile(file, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return ''
    throw error
  })
  return raw.slice(0, raw.lastIndexOf('\n') + 1).split('\n').filter(Boolean).map(JSON.parse)
}

async function open(context, command) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  await opened
  await until(context, () => context.bot.currentWindow?.slots.some(Boolean), 'Season inventory was empty')
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Inventory did not close')
}

async function mode(context, value) {
  if (context.bot.game.gameMode !== value) {
    context.bot.chat(`/gamemode ${value}`)
    await until(context, () => context.bot.game.gameMode === value, `The player did not enter ${value}`)
  }
}

async function clickOpen(context, slot, button = 0, mode = 0) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(slot, button, mode)
  await opened
  await until(context, () => context.bot.currentWindow?.slots[49], 'Replacement inventory did not arrive')
}

async function prompt(context, slot, value) {
  const message = context.waitForMessage(/Type a new value for/i, TIMEOUT)
  await context.bot.clickWindow(slot, 0, 0)
  await message
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(value)
  await opened
  await until(context, () => context.bot.currentWindow?.slots[49], 'Draft inventory did not arrive')
}

export default {
  name: 'static-seasons',
  description: 'Verify frozen season scoring, durable tied results, independent filters, and restart reconciliation.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    context.expect(Boolean(directory), 'STATIC_QA_PLUGIN_DIR is required')
    const file = path.join(directory, 'config.toml')
    const fixture = path.join(path.dirname(directory), 'StaticFixture')
    const eventFile = path.join(fixture, 'season-events.jsonl')
    const baselineFile = path.join(fixture, 'season-baseline.json')
    const phase = process.env.STATIC_QA_MODE ?? 'seed'
    context.report.staticMode = phase
    if (phase === 'restart') {
      const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
      await context.step('restart preserves frozen winners and reconciles the season ended while stopped', async () => {
        await until(context, async () => (await profile(context, 'recovery')).status === 'ARCHIVED', 'Stopped season did not reconcile')
        const board = await ranking(context, 'building')
        context.expect(JSON.stringify(board) === JSON.stringify(baseline.board), 'Archived result changed on restart', board)
        context.expect((await profile(context, 'building')).completedAt === baseline.completedAt, 'Archive completion time changed on restart')
        const recovery = await ranking(context, 'recovery')
        context.expect(recovery.entries.some(entry => entry.uuid === context.bot.player.uuid && entry.value === 3), 'Restart lost recovery score', recovery)
        const events = await callbacks(eventFile)
        context.expect(events.filter(event => event.result.id === 'building').length === 1, 'Restart replayed building completion')
        const recovered = events.filter(event => event.result.id === 'recovery')
        context.expect(recovered.length <= 1 && recovered.every(event => event.reconciled), 'Recovery callback duplicated or incorrectly classified')
        const stored = JSON.parse(await readFile(path.join(directory, 'storage', 'players.json'), 'utf8'))
        const durableRecovery = stored.seasons.find(event => event.id === 'recovery')
        context.expect(durableRecovery?.['archived-at'] >= Date.parse(baseline.events[1].endsAt)
          && durableRecovery.players.some(player => player.uuid === context.bot.player.uuid && player.values.blocks_placed === 3),
        'Recovered archive missing from durable storage', durableRecovery)
        context.report.seasonCallbacks = events.map(({ result, reconciled }) => ({ result, reconciled }))
      })
      await context.step('archived season catalog and ranking remain accessible after restart', async () => {
        await open(context, '/static seasons')
        await close(context)
        await context.command('/static seasontop building', /building|Season Building/i, TIMEOUT)
      })
      await context.step('season editor keeps drafts private until save and can remove scheduled events', async () => {
        const original = await readFile(file, 'utf8')
        await open(context, '/static config')
        await clickOpen(context, 11)
        await prompt(context, 49, 'editor-event')
        await prompt(context, 1, 'Editor Competition')
        context.expect(await readFile(file, 'utf8') === original, 'Draft changed configuration before Save')
        const saved = context.waitForMessage(/Saved event editor-event/, TIMEOUT)
        await clickOpen(context, 49)
        await saved
        context.expect((await readFile(file, 'utf8')).includes('Editor Competition'), 'Saved event missing from config')
        context.expect((await profile(context, 'editor-event')).status === 'SCHEDULED', 'Saved editor event not scheduled')
        await clickOpen(context, 2)
        await prompt(context, 1, 'cancel')
        context.expect((await readFile(file, 'utf8')).includes('Editor Competition'), 'Cancelled prompt changed config')
        const removed = context.waitForMessage(/Removed scheduled event editor-event/, TIMEOUT)
        await clickOpen(context, 51, 1, 1)
        await removed
        context.expect(!(await readFile(file, 'utf8')).includes('editor-event'), 'Removed future event remains in config')
        await close(context)
      })
      return
    }
    const now = Date.now()
    const events = [
      { id: 'building', name: 'Season Building', statistic: 'blocks_broken', startsAt: new Date(now + 2500).toISOString(), endsAt: new Date(now + 27000).toISOString() },
      { id: 'recovery', name: 'Season Recovery', statistic: 'blocks_placed', startsAt: new Date(now + 2500).toISOString(), endsAt: new Date(now + 47000).toISOString() }
    ]
    await context.step('scheduled seasons install without assigning earlier lifetime totals', async () => {
      await mode(context, 'survival')
      await context.command('/staticfixture add blocks_broken 50', /Fixture added/, TIMEOUT)
      await reload(context, file, events)
      context.expect((await profile(context, 'building')).value === 0, 'Historical totals entered season')
      await until(context, async () => (await profile(context, 'building')).status === 'ACTIVE', 'Season did not start')
    })
    await context.step('frozen season rules accept creative activity while lifetime filters exclude it', async () => {
      await mode(context, 'creative')
      await context.command('/staticfixture add blocks_broken 4', /Fixture added/, TIMEOUT)
      context.expect((await profile(context, 'building')).value === 4, 'Season did not use its own eligibility')
      const raw = await context.command('/staticfixture value blocks_broken', /Fixture value blocks_broken /, TIMEOUT)
      context.expect(raw.endsWith('50.0'), 'Season eligibility changed lifetime totals', raw)
    })
    await context.step('real block break scores and offline equal scores share first place', async () => {
      const position = context.bot.entity.position.floored().offset(1, 0, 0)
      context.bot.chat(`/setblock ${position.x} ${position.y} ${position.z} minecraft:stone`)
      await until(context, () => context.bot.blockAt(position)?.name === 'stone', 'Test stone missing')
      await context.bot.dig(context.bot.blockAt(position), true)
      await until(context, async () => (await profile(context, 'building')).value === 5, 'Real break did not score')
      await context.command(`/staticfixture add-uuid ${PEER} blocks_broken 5`, /Fixture UUID added/, TIMEOUT)
      const board = await ranking(context, 'building')
      context.expect(board.entries.length === 2 && board.entries.every(entry => entry.rank === 1 && entry.value === 5), 'Shared tied ranks incorrect', board)
      await context.command('/staticfixture add blocks_placed 3', /Fixture added/, TIMEOUT)
    })
    await context.step('started definitions reject edits without replacing working season rules', async () => {
      await reload(context, file, events.map(event => event.id === 'building' ? { ...event, statistic: 'votes' } : event), true)
      context.expect((await profile(context, 'building')).value === 5, 'Rejected edit changed season scoring')
      await writeFile(file, config(events))
      context.expect((await profile(context, 'building')).value === 5, 'Restoring valid config changed retained score')
    })
    await context.step('season catalog, profile, ranking, and permissions work', async () => {
      await open(context, '/static seasons')
      await close(context)
      await context.command('/static season building', /Season Building|building/i, TIMEOUT)
      await context.command('/static seasontop building', /Season Building|building/i, TIMEOUT)
      await context.command('/staticfixture permission static.seasons false', /Fixture permission/, TIMEOUT)
      await context.command('/static seasons', /permission/i, TIMEOUT)
      await context.command('/staticfixture permission static.seasons true', /Fixture permission/, TIMEOUT)
    })
    await context.step('end freezes tied winners and publishes completion after durable save', async () => {
      await until(context, async () => (await profile(context, 'building')).status === 'ARCHIVED', 'Season did not archive', 40000)
      await until(context, async () => (await callbacks(eventFile)).some(event => event.result.id === 'building'), 'Durable season callback missing')
      const board = await ranking(context, 'building')
      context.expect(board.winners === 2 && board.entries.every(entry => entry.value === 5), 'Frozen tied winners incorrect', board)
      const event = (await callbacks(eventFile)).find(entry => entry.result.id === 'building')
      const durable = event.stored.seasons.find(saved => saved.id === 'building')
      context.expect(durable?.['archived-at'] >= Date.parse(events[0].endsAt)
        && durable.players.length === 2 && durable.players.every(player => player.values.blocks_broken === 5),
      'Callback did not observe the exact saved result', durable)
      await context.command('/staticfixture add blocks_broken 99', /Fixture added/, TIMEOUT)
      context.expect(JSON.stringify(await ranking(context, 'building')) === JSON.stringify(board), 'Post-end activity changed result')
      await writeFile(baselineFile, JSON.stringify({ board, events, completedAt: (await profile(context, 'building')).completedAt,
        eventCount: (await callbacks(eventFile)).length }))
    })
    context.report.seasonEndWhileStopped = events[1].endsAt
  }
}
