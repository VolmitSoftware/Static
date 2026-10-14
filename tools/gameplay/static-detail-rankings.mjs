import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 15000
const COUNTER = 'mined_diamond_ore'
const EMPTY = 'mined_iron_ore'
const ZERO = '7169b1a1-a1a1-4d01-8000-000000000099'
const HIGH = [
  ['7169b1a1-a1a1-4d01-8000-000000000001', '9007199254740995'],
  ['7169b1a1-a1a1-4d01-8000-000000000002', '9007199254740993'],
  ['7169b1a1-a1a1-4d01-8000-000000000003', '9007199254740993']
]
const FILLERS = Array.from({ length: 10 }, (_, index) =>
  [`7169b1a1-a1a1-4d01-8000-${String(index + 10).padStart(12, '0')}`, String(100 + index)])

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function board(context, key = COUNTER, cached = false) {
  const response = await context.command(`/staticfixture detail-board ${key}${cached ? ' cached' : ''}`,
    /^Fixture detail board requested [a-f0-9-]{36}$/, TIMEOUT)
  const request = response.slice('Fixture detail board requested '.length)
  await until(context, async () => {
    const status = await context.command(`/staticfixture detail-board-ready ${request}`,
      new RegExp(`^Fixture detail board (pending|saved|failed) ${request}$`), TIMEOUT)
    context.expect(!status.includes('failed'), 'Detailed ranking fixture request failed', { request })
    return status.includes('saved')
  }, 'Detailed ranking fixture request did not finish')
  const file = path.join(path.dirname(process.env.STATIC_QA_PLUGIN_DIR), 'StaticFixture', 'detail-ranking-results', `${request}.json`)
  return JSON.parse(await readFile(file, 'utf8'))
}

function configuration(enabled = true, selected = [COUNTER, EMPTY]) {
  return `[tracking]\ntrackCreative = false\nautosaveSeconds = 10\nleaderboardLimit = 10\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[details]\nenabled = ${enabled}\nselectedKeys = ${JSON.stringify(selected)}\n`
}

async function reload(context, enabled = true, selected = [COUNTER, EMPTY]) {
  const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(path.join(process.env.STATIC_QA_PLUGIN_DIR, 'config.toml'), configuration(enabled, selected))
  await applied
}

async function synchronize(context, label) {
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`${label}: inventory synchronization timed out`)), TIMEOUT)
    context.bot._syncWindow(context.bot.inventory).then(() => {
      clearTimeout(timeout)
      resolve()
    }, error => {
      clearTimeout(timeout)
      reject(error)
    })
  })
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Detailed inventory did not close')
  await synchronize(context, 'Detailed inventory close')
}

function text(component) {
  if (component === undefined || component === null) return ''
  if (typeof component === 'string') {
    if (/^\s*(?:\{|\[|")/.test(component)) {
      try { return text(JSON.parse(component)) } catch {}
    }
    return component.replace(/§[0-9a-fk-orx]/gi, '')
  }
  if (Array.isArray(component)) return component.map(text).join('')
  if (typeof component !== 'object') return String(component)
  if ('type' in component && 'value' in component) return text(component.value)
  return text(component.text) + text(component.extra)
}

function itemText(item) {
  const lore = item?.components?.find(component => component.type === 'lore')?.data
    ?? item?.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [text(item?.customName), ...lore.map(text)].join('\n')
}

async function chatResult(context, action, patterns) {
  const messages = []
  const listener = message => messages.push(String(message))
  context.bot.on('messagestr', listener)
  try {
    await action()
    await until(context, () => patterns.every(pattern => messages.some(message => pattern.test(message))),
      `Expected ranking feedback did not arrive: ${patterns.join(', ')}`)
    return messages
  } finally {
    context.bot.removeListener('messagestr', listener)
  }
}

function grouped(value) {
  return BigInt(value).toLocaleString('en-US')
}

function assertRanking(context, snapshot, uuid) {
  context.expect(snapshot?.counter === COUNTER && snapshot.entries.length === 14,
    'Detailed ranking did not contain exactly fourteen positive profiles', snapshot)
  context.expect(snapshot.totalProfiles === 15 && snapshot.excludedCount === 1,
    'Zero-observation profiles were not excluded consistently', snapshot)
  context.expect(snapshot.recordedSince > 0 && snapshot.recordedSince <= snapshot.refreshedAt,
    'Detailed ranking lacks recorded coverage', snapshot)
  context.expect(!snapshot.entries.some(entry => entry.uuid === ZERO), 'A zero-count profile entered the board')
  const expected = [...HIGH, ...FILLERS.slice().reverse(), [uuid, '1']]
  for (let index = 0; index < expected.length; index++) {
    const entry = snapshot.entries[index]
    const [id, value] = expected[index]
    context.expect(typeof entry.value === 'string' && typeof entry.gapToNext === 'string',
      'Long counter values must reach the fixture as decimal strings', entry)
    const previousHigher = snapshot.entries.slice(0, index).reverse().find(previous => BigInt(previous.value) > BigInt(value))
    const gap = previousHigher ? BigInt(previousHigher.value) - BigInt(value) : 0n
    const rank = index === 2 ? 2 : index + 1
    context.expect(entry.uuid === id && BigInt(entry.value) === BigInt(value)
      && entry.position === index + 1 && entry.rank === rank && BigInt(entry.gapToNext) === gap,
    'Exact integer ordering, tie rank, row position, or gap is incorrect', { entry, expected: { id, value, rank, gap: String(gap) } })
  }
}

async function add(context, uuid, value) {
  await context.command(`/staticfixture detail-add ${COUNTER} ${value}${uuid ? ` ${uuid}` : ''}`, /^Fixture detail added$/, TIMEOUT)
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'Statistics flush failed')
    return response.includes('complete')
  }, 'Statistics flush did not finish')
}

export default {
  name: 'static-detail-rankings',
  description: 'Verify exact long detailed rankings, offline ties, lifetime scope, menu shortcuts, permissions, retained data, and restart persistence.',
  async run(context) {
    context.expect(Boolean(process.env.STATIC_QA_PLUGIN_DIR), 'STATIC_QA_PLUGIN_DIR is required')
    const fixture = path.join(path.dirname(process.env.STATIC_QA_PLUGIN_DIR), 'StaticFixture')
    const baselineFile = path.join(fixture, 'detail-ranking-baseline.json')
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.report.staticMode = mode
    const uuid = context.report.bot.uuid
    context.expect(typeof uuid === 'string' && uuid === context.bot.player?.uuid, 'The joined bot identity is unavailable')
    if (mode === 'restart') {
      const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
      await context.step('restart preserves exact long values, ties, gaps, and observation coverage while disabled', async () => {
        const restored = await board(context)
        assertRanking(context, restored, uuid)
        context.expect(JSON.stringify(restored.entries) === JSON.stringify(baseline.entries)
          && restored.recordedSince === baseline.recordedSince, 'Restart changed the retained detailed ranking', { baseline, restored })
        context.report.restoredRanking = restored
      })
      await context.step('retained disabled rankings remain available through commands and empty-board feedback', async () => {
        const messages = await chatResult(context, () => context.bot.chat(`/static detailtop ${COUNTER} 2`),
          [/rankings.*page 2\/2/, /Lifetime detailed counts.*14\/15/, /Your rank: #14 of 14 players/, /Gap to the next higher score: 99/])
        await chatResult(context, () => context.bot.chat(`/static detailtop ${EMPTY}`),
          [/No positive recorded counts for this counter yet/, /You have no positive recorded count/])
        context.report.restoredMessages = messages
      })
      return
    }
    let seeded
    await reload(context)
    await context.step('unobserved detailed counters have an empty positive-only ranking', async () => {
      const empty = await board(context, EMPTY)
      context.expect(empty.entries.length === 0 && empty.recordedSince === 0 && empty.excludedCount === empty.totalProfiles,
        'Empty detailed ranking fabricated observations', empty)
      context.report.emptyRanking = empty
    })
    await context.step('one server-confirmed survival ore break enters its detailed leaderboard', async () => {
      if (context.bot.game.gameMode !== 'survival') {
        context.bot.chat('/gamemode survival')
        await until(context, () => context.bot.game.gameMode === 'survival', 'Survival mode was not applied')
      }
      await context.command('/execute if entity @s[gamemode=survival] run tellraw @s "Detail survival confirmed"', /Detail survival confirmed/, TIMEOUT)
      await context.command('/give @s minecraft:netherite_pickaxe 1', /Gave /, TIMEOUT)
      await synchronize(context, 'Pickaxe grant')
      const pickaxe = context.bot.inventory.items().find(item => item.name === 'netherite_pickaxe')
      context.expect(Boolean(pickaxe), 'Pickaxe missing')
      await context.bot.equip(pickaxe, 'hand')
      await synchronize(context, 'Pickaxe equip')
      await context.command('/execute if items entity @s weapon.mainhand minecraft:netherite_pickaxe run tellraw @s "Detail tool confirmed"', /Detail tool confirmed/, TIMEOUT)
      const position = context.bot.entity.position.floored().offset(2, 0, 0)
      context.bot.chat(`/setblock ${position.x} ${position.y} ${position.z} minecraft:diamond_ore`)
      await until(context, () => context.bot.blockAt(position)?.name === 'diamond_ore', 'Diamond ore did not arrive')
      await context.command(`/execute if block ${position.x} ${position.y} ${position.z} minecraft:diamond_ore run tellraw @s "Detail ore ready"`, /Detail ore ready/, TIMEOUT)
      await context.bot.dig(context.bot.blockAt(position), true)
      let acknowledgment
      await until(context, async () => {
        acknowledgment = await context.command(`/execute if block ${position.x} ${position.y} ${position.z} minecraft:air`, /Test (?:passed|failed)/, TIMEOUT)
        return acknowledgment.includes('Test passed')
      }, 'The server did not acknowledge the single physical ore break')
      let result
      await until(context, async () => {
        result = await board(context)
        context.expect(result.entries.every(entry => BigInt(entry.value) <= 1n), 'The single ore break counted more than once')
        return result.entries.length === 1 && result.entries[0].uuid === uuid && result.entries[0].value === '1'
      }, 'The physical ore break did not enter the detailed ranking')
      context.report.actualOre = { position, acknowledgment, ranking: result }
    })
    await context.step('offline values above JavaScript integer precision keep exact order, competition ties, and gaps', async () => {
      for (const [id, value] of [...HIGH, ...FILLERS]) await add(context, id, value)
      await context.command(`/staticfixture add-uuid ${ZERO} blocks_broken 1`, /Fixture UUID added/, TIMEOUT)
      await until(context, async () => {
        seeded = await board(context)
        return seeded.entries.length === 14 && seeded.totalProfiles === 15
      }, 'Fresh detailed ranking did not incorporate all offline additions')
      assertRanking(context, seeded, uuid)
      const cached = await board(context, COUNTER, true)
      context.expect(cached?.refreshedAt === seeded.refreshedAt && JSON.stringify(cached.entries) === JSON.stringify(seeded.entries),
        'Cached detailed ranking differs from the completed snapshot', { seeded, cached })
      context.report.seededRanking = seeded
    })
    await context.step('paged chat shares exact values, personal rank, nearby rows, and lifetime scope', async () => {
      const messages = await chatResult(context, () => context.bot.chat(`/static detailtop ${COUNTER} 1`),
        [/rankings.*page 1\/2/, /Lifetime detailed counts.*14\/15/, /Your rank: #14 of 14 players/, /Gap to the next higher score: 99/,
          /Players near your position/, new RegExp(`#1 ${HIGH[0][0]}: ${grouped(HIGH[0][1])}`),
          new RegExp(`#2 ${HIGH[1][0]}: ${grouped(HIGH[1][1])}`), new RegExp(`#2 ${HIGH[2][0]}: ${grouped(HIGH[2][1])}`),
          new RegExp(`#14 ${context.bot.username}: 1`)])
      await chatResult(context, () => context.bot.chat(`/static detailtop ${COUNTER} 2`),
        [/rankings.*page 2\/2/, new RegExp(`#14 ${context.bot.username}: 1`), /Gap to the next higher score: 99/])
      context.report.rankingMessages = messages
    })
    await context.step('invalid counters, invalid pages, and empty boards explain their result', async () => {
      await context.command('/static detailtop invalid_counter', /Unknown detailed counter: invalid_counter/, TIMEOUT)
      await context.command(`/static detailtop ${COUNTER} 0`, /That page is outside the available rankings/, TIMEOUT)
      await context.command(`/static detailtop ${COUNTER} 999`, /That page is outside the available rankings/, TIMEOUT)
      await chatResult(context, () => context.bot.chat(`/static detailtop ${EMPTY}`),
        [/No positive recorded counts for this counter yet/, /You have no positive recorded count/])
    })
    await context.step('detail ranking permissions deny the command and restore its use', async () => {
      await context.command('/staticfixture permission static.detailtop false', /Fixture permission static.detailtop false/, TIMEOUT)
      await context.command(`/static detailtop ${COUNTER}`, /You do not have permission to do that/, TIMEOUT)
      await context.command('/staticfixture permission static.detailtop true', /Fixture permission static.detailtop true/, TIMEOUT)
      await chatResult(context, () => context.bot.chat(`/static detailtop ${COUNTER}`), [/rankings.*page 1\/2/, /Your rank: #14/])
    })
    await context.step('clicking a recorded detail opens the same chat ranking and keeps the menu usable', async () => {
      const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('/static details')
      await opened
      const window = context.bot.currentWindow
      context.expect(window?.inventoryStart === 54, 'Detailed counters menu did not open')
      const slot = window.slots.slice(0, 45).findIndex(item => itemText(item).includes('Diamond ore mined'))
      context.expect(slot >= 0 && itemText(window.slots[slot]).includes('Click to view lifetime rankings for this counter.'),
        'Recorded diamond-ore counter lacks its ranking shortcut', window.slots.slice(0, 45).map(itemText))
      await chatResult(context, () => context.bot.clickWindow(slot, 0, 0), [/rankings.*page 1\/2/, /Your rank: #14/, /Gap to the next higher score: 99/])
      context.expect(context.bot.currentWindow === window, 'The chat ranking unexpectedly replaced or closed the detail menu')
      await close(context)
    })
    await context.step('deselection stops collection while retaining the exact lifetime board', async () => {
      await reload(context, true, [EMPTY])
      await add(context, '', '7')
      const result = await context.command(`/staticfixture detail ${COUNTER}`, new RegExp(`^Fixture detail ${COUNTER} `), TIMEOUT)
      context.expect(result.split(' ')[3] === '1', 'Deselected detailed counter accepted an increment', { result })
      assertRanking(context, await board(context), uuid)
    })
    await context.step('disabling detailed collection keeps recorded rankings readable', async () => {
      await reload(context, false)
      await add(context, '', '9')
      const result = await context.command(`/staticfixture detail ${COUNTER}`, new RegExp(`^Fixture detail ${COUNTER} `), TIMEOUT)
      context.expect(result.split(' ')[3] === '1', 'Disabled detailed counter accepted an increment', { result })
      assertRanking(context, await board(context), uuid)
      await chatResult(context, () => context.bot.chat(`/static detailtop ${COUNTER} 2`), [/rankings.*page 2\/2/, /Your rank: #14/])
    })
    await context.step('exact ranking values and coverage are saved for restart comparison', async () => {
      await flush(context)
      const saved = await board(context)
      assertRanking(context, saved, uuid)
      await mkdir(fixture, { recursive: true })
      await writeFile(baselineFile, JSON.stringify(saved, null, 2))
      context.report.savedRanking = saved
    })
  }
}
