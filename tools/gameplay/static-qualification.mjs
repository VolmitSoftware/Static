import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 10000
const POLICIES = [
  ['kill_death_ratio', 10, 20, 10, 'pvp_kills', 'pvp_deaths'],
  ['melee_damage_ratio', 100, 200, 100, 'melee_damage_dealt', 'melee_damage_taken'],
  ['melee_hit_ratio', 25, 50, 25, 'melee_hits_dealt', 'melee_hits_taken'],
  ['arrow_damage_ratio', 100, 200, 100, 'arrow_damage_dealt', 'arrow_damage_taken'],
  ['arrow_hit_ratio', 25, 25, 25, 'arrow_hits_dealt', 'arrow_hits_taken'],
  ['arrow_accuracy', 50, 25, 50, 'arrow_hits_dealt', 'arrows_fired'],
  ['votes_per_day', 3600000, 2, 3600000, 'votes', 'online_time']
]

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
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

async function open(context, command) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  await opened
  await until(context, () => context.bot.currentWindow?.slots.some(Boolean), 'Inventory contents did not arrive')
  return context.bot.currentWindow
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Inventory did not close')
}

function slot(context, pattern) {
  const window = context.bot.currentWindow
  const found = window.slots.slice(0, window.inventoryStart).findIndex(item => pattern.test(itemText(item)))
  context.expect(found >= 0, `Missing inventory control ${pattern}`)
  return found
}

async function transition(context, selected) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(selected, 0, 0)
  await opened
  await until(context, () => context.bot.currentWindow?.slots.some(Boolean), 'Updated inventory contents did not arrive')
}

async function placeholder(context, parameter) {
  const response = await context.command(`/papi parse me QABEGIN%static_${parameter}%QAEND`, /^QABEGIN.*QAEND$/, TIMEOUT)
  const value = response.match(/^QABEGIN(.*?)QAEND$/)[1]
  context.expect(!value.includes('%static_'), 'Static placeholder was not resolved', { parameter, value })
  return value
}

async function qualification(context, stat, name = '') {
  const response = await context.command(`/staticfixture qualification ${stat} ${name}`.trim(), /Fixture qualification /, TIMEOUT)
  const match = response.match(/Fixture qualification (\w+) (true|false) ([0-9.e+-]+) ([0-9.e+-]+) ([0-9.e+-]+)/)
  return match ? { stat: match[1], qualified: match[2] === 'true', numerator: Number(match[3]), denominator: Number(match[4]), minimum: Number(match[5]) } : null
}

async function ranking(context, stat) {
  await context.command(`/staticfixture ranking ${stat} request`, /Fixture ranking requested/, TIMEOUT)
  let snapshot
  await until(context, async () => {
    const response = await context.command(`/staticfixture ranking ${stat} ready`, /Fixture ranking /, TIMEOUT)
    const match = response.match(/Fixture ranking (\w+) (\d+)(?: (.*))?$/)
    if (!match) return false
    snapshot = { stat: match[1], refreshedAt: Number(match[2]), entries: (match[3] ?? '').split(';').filter(Boolean).map(row => {
      const [uuid, position, rank, value, gap] = row.split(',')
      return { uuid, position: Number(position), rank: Number(rank), value: Number(value), gap: Number(gap) }
    }) }
    return true
  }, `Ranking ${stat} did not finish`)
  return snapshot
}

async function capture(context, command, patterns) {
  const messages = []
  const listener = message => messages.push(message)
  context.bot.on('messagestr', listener)
  try {
    context.bot.chat(command)
    await until(context, () => patterns.every(pattern => messages.some(message => pattern.test(message))),
      `Expected qualification messages did not arrive for ${command}`)
    return messages
  } finally {
    context.bot.removeListener('messagestr', listener)
  }
}

async function waitPolicy(context, minimum) {
  let snapshot
  await until(context, async () => {
    snapshot = await ranking(context, 'kill_death_ratio')
    return (await qualification(context, 'kill_death_ratio'))?.minimum === minimum
  }, `The runtime did not apply qualification minimum ${minimum}`)
  return snapshot
}

async function writeThreshold(context, directory, minimum) {
  const file = path.join(directory, 'config.toml')
  const source = await readFile(file, 'utf8')
  context.expect(/^minPvpDeaths\s*=/m.test(source), 'The canonical ranking threshold was not generated')
  await writeFile(file, source.replace(/^minPvpDeaths\s*=.*$/m, `minPvpDeaths = ${minimum}`))
  return waitPolicy(context, minimum)
}

export default {
  name: 'static-qualification',
  description: 'Check ratio sample eligibility, raw totals, qualifying placeholders, hot reload, editor persistence, and restart.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory, 'Set STATIC_QA_PLUGIN_DIR to the isolated Static data folder')
    context.expect(mode === 'seed' || mode === 'restart', 'STATIC_QA_MODE must be seed or restart')
    context.report.staticMode = mode
    await context.command('/staticfixture', /Fixture ready/, TIMEOUT)

    if (mode === 'restart') {
      await context.step('edited qualification threshold and qualified rank survive restart', async () => {
        const board = await waitPolicy(context, 1)
        context.expect(board.entries.length === 3, 'Restart lost the edited eligibility policy')
        context.expect(await placeholder(context, 'qualification_kill_death_ratio_qualified') === 'true', 'Restart changed personal eligibility')
        context.expect(await placeholder(context, 'qualification_kill_death_ratio_minimum') === '1', 'Restart lost the minimum')
        context.expect(await placeholder(context, 'kill_death_ratio') === '1', 'Raw ratio changed across restart')
        context.expect(await placeholder(context, 'rank_kill_death_ratio') === '3', 'Restart lost the qualified personal rank')
      })
      return
    }

    await context.step('unqualified profile retains its raw base statistics and ratio', async () => {
      for (const stat of ['pvp_kills', 'pvp_deaths']) await context.command(`/staticfixture add ${stat} 1`, /Fixture added/, TIMEOUT)
      context.expect(await placeholder(context, 'pvp_kills') === '1', 'Qualification altered the raw numerator')
      context.expect(await placeholder(context, 'pvp_deaths') === '1', 'Qualification altered the raw denominator')
      context.expect(await placeholder(context, 'kill_death_ratio') === '1', 'Qualification altered the raw ratio')
    })

    await context.step('all seven ratio boards exclude small samples and include the exact default boundary', async () => {
      const results = []
      for (const [stat, minimum, numerator, denominator] of POLICIES) {
        const board = await ranking(context, stat)
        context.expect(board.entries.length === 1 && board.entries[0].uuid === '00000000-0000-4000-8000-000000000012',
          'A ratio board ranked a sample below its minimum or omitted the exact boundary', { stat, board })
        const boundary = await qualification(context, stat, 'BoundarySample')
        const small = await qualification(context, stat, 'SmallSample')
        context.expect(boundary?.qualified && boundary.minimum === minimum && boundary.denominator === denominator
          && boundary.numerator === numerator, 'Exact-boundary qualification metadata is incorrect', { stat, boundary })
        context.expect(small !== null && !small.qualified && small.denominator < minimum,
          'Small-sample profile incorrectly qualified', { stat, small })
        context.expect(await placeholder(context, `qualification_${stat}_minimum`) === String(minimum),
          'Placeholder minimum did not use native denominator units', { stat, minimum })
        results.push({ stat, board, boundary, small })
      }
      context.report.staticQualification = results
    })

    await context.step('cached qualification placeholders expose sample progress and omit unqualified rank', async () => {
      await ranking(context, 'kill_death_ratio')
      const expected = { qualified: 'false', numerator: '1', denominator: '1', minimum: '10', remaining: '9' }
      for (const [field, value] of Object.entries(expected)) {
        context.expect(await placeholder(context, `qualification_kill_death_ratio_${field}`) === value,
          'Qualification placeholder differs from the cached sample', { field, value })
      }
      context.expect(await placeholder(context, 'rank_kill_death_ratio') === '', 'Unqualified profile received a rank')
      context.expect(await placeholder(context, 'position_kill_death_ratio') === '', 'Unqualified profile received a position')
      context.expect(await placeholder(context, 'top_kill_death_ratio_1_name') === 'BoundarySample', 'Small-sample profile leads the ranked board')
      context.expect(await placeholder(context, 'qualification_blocks_broken_qualified') === '', 'A base statistic exposed ratio qualification')
    })

    await context.step('personal ranking output and profile lore explain the required sample', async () => {
      context.report.staticUnqualifiedMessages = await capture(context, '/static top kill_death_ratio',
        [/BoundarySample/, /You are not qualified for this ranking yet\./,
          /Your Player combat deaths: 1 \/ minimum 10/, /Qualified: 1\/3 profiles.*Excluded: 2/])
      await open(context, '/stats')
      const lore = itemText(context.bot.currentWindow.slots[slot(context, /^Kill\/death ratio\b/)])
      context.report.staticQualificationLore = lore
      context.expect(/Numerator · Player kills: 1/.test(lore) && /Denominator · Player combat deaths: 1/.test(lore)
        && /Ranking minimum · Player combat deaths: 10/.test(lore) && /Not qualified for this ranking yet/.test(lore),
        'Ratio lore did not show numerator, denominator, minimum, and qualification state', { lore })
      await close(context)
    })

    await context.step('raising the minimum hot reloads an empty qualified board with an explanation', async () => {
      const board = await writeThreshold(context, directory, 11)
      context.expect(board.entries.length === 0, 'A warmed ranking retained players under an outdated policy')
      context.expect(await placeholder(context, 'qualification_kill_death_ratio_minimum') === '11', 'PAPI retained the previous policy')
      context.expect(await placeholder(context, 'top_kill_death_ratio_1_name') === '', 'An empty qualified board exposed an old top player')
      context.report.staticEmptyMessages = await capture(context, '/static top kill_death_ratio',
        [/No recorded profiles meet this ranking's requirements yet\./, /Qualified: 0\/3 profiles.*Excluded: 3/,
          /Your Player combat deaths: 1 \/ minimum 11/])
      context.expect(await placeholder(context, 'pvp_deaths') === '1', 'Changing qualification mutated base counters')
    })

    await context.step('lowering the minimum refreshes the board without retaining the previous eligibility policy', async () => {
      const board = await writeThreshold(context, directory, 9)
      context.expect(board.entries.length === 2 && board.entries[0].uuid === '00000000-0000-4000-8000-000000000011',
        'The newly qualified higher ratio did not lead the refreshed board', { board })
      context.expect(await placeholder(context, 'top_kill_death_ratio_1_name') === 'SmallSample', 'PAPI did not use the newly qualified board')
      context.expect(await placeholder(context, 'qualification_kill_death_ratio_minimum') === '9', 'PAPI retained a prior minimum')
      context.expect(await placeholder(context, 'rank_kill_death_ratio') === '', 'The personal profile qualified below the new threshold')
    })

    await context.step('zero minimum still excludes zero denominators from ratio boards', async () => {
      const file = path.join(directory, 'config.toml')
      const source = await readFile(file, 'utf8')
      context.expect(/^minArrowsFired\s*=/m.test(source), 'The arrow sample setting is missing')
      await writeFile(file, source.replace(/^minArrowsFired\s*=.*$/m, 'minArrowsFired = 0'))
      await until(context, async () => {
        await ranking(context, 'arrow_accuracy')
        return (await qualification(context, 'arrow_accuracy'))?.minimum === 0
      }, 'The zero minimum was not applied')
      context.expect(await placeholder(context, 'qualification_arrow_accuracy_qualified') === 'false', 'A zero denominator qualified')
      context.expect(await placeholder(context, 'qualification_arrow_accuracy_denominator') === '0', 'Zero denominator metadata changed')
      context.expect(await placeholder(context, 'rank_arrow_accuracy') === '', 'A zero-denominator profile received a rank')
    })

    await context.step('configuration editor saves the qualification threshold and updates the personal rank', async () => {
      await open(context, '/static config')
      await transition(context, slot(context, /^Rankings?\b/))
      const setting = slot(context, /deaths/i)
      await context.bot.clickWindow(setting, 0, 4)
      await until(context, () => context.bot.currentWindow === null, 'Qualification editor did not request numeric input')
      const reopened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('1')
      await reopened
      await until(context, () => /Current:\s*1\b/.test(itemText(context.bot.currentWindow.slots[setting])),
        'Saved minimum did not appear in the editor')
      const hours = slot(context, /Minimum online hours/)
      await transition(context, hours)
      context.expect(/Current:\s*2(?:\.0)?\b/.test(itemText(context.bot.currentWindow.slots[hours])),
        'The online-hours left click did not increase the minimum by one')
      const restored = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      await context.bot.clickWindow(hours, 1, 0)
      await restored
      await until(context, () => /Current:\s*1(?:\.0)?\b/.test(itemText(context.bot.currentWindow.slots[hours])),
        'The online-hours right click did not restore the minimum to one')
      await close(context)
      const board = await waitPolicy(context, 1)
      context.expect(board.entries.length === 3, 'The editor policy did not include the newly qualified profile')
      context.expect(await placeholder(context, 'qualification_kill_death_ratio_qualified') === 'true', 'The boundary profile did not qualify')
      context.expect(await placeholder(context, 'rank_kill_death_ratio') === '3', 'Qualified personal rank is incorrect')
      context.report.staticQualifiedMessages = await capture(context, '/static top kill_death_ratio', [/Your rank: #3 of 3/, /Qualified: 3\/3 profiles/])
      context.expect(/^minPvpDeaths\s*=\s*1(?:\.0)?\s*$/m.test(await readFile(path.join(directory, 'config.toml'), 'utf8')),
        'The edited threshold was not written to the canonical TOML')
    })

    await context.step('qualified ranking data and unmodified raw totals are durably saved', async () => {
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => {
        const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
        context.expect(!response.includes('failed'), 'Statistics save failed')
        return response.includes('complete')
      }, 'Statistics save did not complete')
      const source = JSON.parse(await readFile(path.join(directory, 'storage', 'players.json'), 'utf8'))
      const player = source.players.find(entry => entry.uuid === context.report.bot.uuid)
      context.expect(player?.values.pvp_kills === 1 && player?.values.pvp_deaths === 1, 'Stored base totals changed during qualification updates')
    })
  }
}
