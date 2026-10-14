import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 8000
const FAVORITES = ['blocks_broken', 'blocks_placed', 'online_time', 'pvp_kills',
  'pvp_deaths', 'deaths', 'mob_kills', 'melee_damage_dealt']
const FAVORITE_LABELS = ['Blocks broken', 'Blocks placed', 'Time online', 'Player kills',
  'Player combat deaths', 'All deaths', 'Mob kills', 'Melee damage dealt']

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(75)
  }
  context.expect(false, message)
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
  if (!item) return ''
  const lore = item.components?.find(component => component.type === 'lore')?.data
    ?? item.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [text(item.customName), ...lore.map(text)].join('\n')
}

function statisticsItems(context) {
  return context.bot.currentWindow?.slots.slice(0, 45).filter(Boolean) ?? []
}

async function open(context, command = '/stats') {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  const [window] = await opened
  context.expect(window.inventoryStart === 54, 'Expected the 54-slot profile inventory')
  await until(context, () => context.bot.currentWindow?.slots[49], 'Profile controls did not arrive')
  return context.bot.currentWindow
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Profile inventory did not close')
}

async function placeholder(context, parameter, target = 'me') {
  const response = await context.command(`/papi parse ${target} QABEGIN%static_${parameter}%QAEND`, /QABEGIN.*QAEND/, TIMEOUT)
  const result = response.match(/QABEGIN(.*?)QAEND/)[1]
  context.expect(!result.includes('%static_'), 'PlaceholderAPI left the Static placeholder unresolved', { parameter, result })
  return result
}

async function profile(context, name = '') {
  const response = await context.command(`/staticfixture profile ${name}`.trim(), /Fixture profile [0-9a-f-]+ \d+/, TIMEOUT)
  const match = response.match(/Fixture profile ([0-9a-f-]+) (\d+)/)
  return { uuid: match[1], recordedSince: Number(match[2]) }
}

function parseRanking(response) {
  const match = response.match(/Fixture ranking (\w+) (\d+) (.*)/)
  if (!match) return null
  return {
    statistic: match[1], refreshedAt: Number(match[2]),
    entries: match[3].split(';').filter(Boolean).map(value => {
      const [uuid, position, rank, amount, gapToNext] = value.split(',')
      return { uuid, position: Number(position), rank: Number(rank), value: Number(amount), gapToNext: Number(gapToNext) }
    })
  }
}

async function ranking(context) {
  await context.command('/staticfixture ranking blocks_broken request', /Fixture ranking requested/, TIMEOUT)
  let result
  await until(context, async () => {
    result = parseRanking(await context.command('/staticfixture ranking blocks_broken ready', /Fixture ranking /, TIMEOUT))
    return result !== null
  }, 'The public asynchronous ranking did not complete')
  return result
}

async function preferences(context, directory) {
  const contents = await readFile(path.join(directory, 'storage', 'profile-preferences.json'), 'utf8').catch(error => {
    if (error.code === 'ENOENT') return '{"version":1,"players":{}}'
    throw error
  })
  const source = JSON.parse(contents)
  context.expect(source.version === 1, 'Unexpected profile preferences format')
  return source.players[context.report.bot.uuid] ?? []
}

async function capturedCommand(context, command, conditions) {
  const messages = []
  const listener = message => messages.push(message)
  context.bot.on('messagestr', listener)
  try {
    context.bot.chat(command)
    await until(context, () => conditions.every(condition => messages.some(message => condition.test(message))),
      'Ranking output did not contain the expected snapshot rows and personal context')
    return messages
  } finally {
    context.bot.removeListener('messagestr', listener)
  }
}

async function favorites(context, directory) {
  await context.step('profile refresh remains usable across consecutive inventory replacements', async () => {
    await open(context)
    for (let attempt = 0; attempt < 3; attempt++) {
      const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      await context.bot.clickWindow(49, 0, 0)
      await opened
      await until(context, () => statisticsItems(context).length >= 30, 'Refresh did not repopulate the profile')
      context.expect(text(context.bot.currentWindow.title).includes(context.bot.username), 'Refresh replaced the selected profile')
      context.expect(itemText(context.bot.currentWindow.slots[0]).includes('1,250'), 'Refresh lost the current statistic value')
    }
    await close(context)
  })

  await context.step('category selection restricts the profile to its matching statistics', async () => {
    await open(context)
    await context.bot.clickWindow(45, 0, 0)
    await until(context, () => statisticsItems(context).length === 2, 'Building category did not show exactly two statistics')
    context.expect(itemText(context.bot.currentWindow.slots[0]).includes('Blocks broken'), 'Building category lost blocks broken')
    context.expect(itemText(context.bot.currentWindow.slots[1]).includes('Blocks placed'), 'Building category lost blocks placed')
    for (let attempt = 0; attempt < 7 && statisticsItems(context).length < 30; attempt++) {
      const previous = context.bot.currentWindow
      await context.bot.clickWindow(45, 0, 0)
      await until(context, () => context.bot.currentWindow !== previous, 'Category selection did not replace the profile view')
    }
    context.expect(statisticsItems(context).length >= 30, 'Category selection did not return to all statistics')
  })

  await context.step('empty favorites explain the state and return to all statistics', async () => {
    await context.bot.clickWindow(46, 0, 0)
    await until(context, () => context.bot.currentWindow?.slots[22]?.name === 'paper',
      'Empty favorites did not display its explanation')
    context.expect(/favorite/i.test(itemText(context.bot.currentWindow.slots[22])), 'Empty favorites has no explanatory text')
    await context.bot.clickWindow(46, 0, 0)
    await until(context, () => statisticsItems(context).length >= 30, 'Empty favorites did not return to all statistics')
  })

  await context.step('eight favorites persist in click order and the ninth is rejected', async () => {
    for (let index = 0; index < FAVORITES.length; index++) {
      const slot = context.bot.currentWindow.slots.slice(0, 45)
        .findIndex(item => itemText(item).split('\n')[0].includes(FAVORITE_LABELS[index]))
      context.expect(slot >= 0, 'The statistic to favorite is missing', { label: FAVORITE_LABELS[index] })
      const previous = context.bot.currentWindow
      await context.bot.clickWindow(slot, 1, 0)
      await until(context, async () => (await preferences(context, directory)).length === index + 1,
        'The selected favorite was not persisted')
      await until(context, () => context.bot.currentWindow !== null && context.bot.currentWindow !== previous,
        'The saved favorite did not refresh the profile view')
    }
    const ninth = context.bot.currentWindow.slots.slice(0, 45)
      .findIndex(item => itemText(item).split('\n')[0].includes('Melee damage taken'))
    context.expect(ninth >= 0, 'The ninth statistic is missing')
    const limit = context.waitForMessage(/8|eight/i, TIMEOUT)
    await context.bot.clickWindow(ninth, 1, 0)
    await limit
    context.expect(JSON.stringify(await preferences(context, directory)) === JSON.stringify(FAVORITES),
      'Favorite order or maximum count differs from the selected eight statistics')
    await context.bot.clickWindow(46, 0, 0)
    await until(context, () => statisticsItems(context).length === 8, 'Favorites view did not contain eight items')
    FAVORITE_LABELS.forEach((label, slot) => context.expect(itemText(context.bot.currentWindow.slots[slot]).includes(label),
      'Favorites view changed the selected order', { slot, label }))
    await close(context)
  })
}

async function permissions(context) {
  await context.step('revoked profile permission closes the existing view on interaction', async () => {
    await open(context)
    try {
      await context.command('/staticfixture permission static.stats false', /Fixture permission static.stats false/, TIMEOUT)
      await context.bot.clickWindow(49, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'A profile remained interactive after permission revocation')
      await context.command('/stats', /permission/i, TIMEOUT)
      context.expect(context.bot.currentWindow === null, 'A denied profile command opened an inventory')
    } finally {
      await context.command('/staticfixture permission static.stats clear', /Fixture permission static.stats true/, TIMEOUT)
    }
  })

  await context.step('a synthetically delayed profile request cannot replace a newer editor view', async () => {
    await open(context)
    await context.command('/staticfixture delay-profile', /Fixture profile queue delayed/, TIMEOUT)
    const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
    context.bot.chat('/static stats StaticLeader')
    context.bot.chat('/static config')
    await opened
    await until(context, () => itemText(context.bot.currentWindow?.slots[20]).includes('General'),
      'The newer configuration editor did not open')
    const editor = context.bot.currentWindow
    await until(context, async () => {
      const response = await context.command('/staticfixture delay-status', /Fixture profile queue (ready|delayed)/, TIMEOUT)
      return response.endsWith('ready')
    }, 'The bounded profile delay did not finish')
    await context.sleep(300)
    context.expect(context.bot.currentWindow === editor, 'An older profile completion replaced the newer editor view')
    await close(context)
  })
}

async function localized(context) {
  await context.step('German profile and PAPI formatting preserves locale-neutral numeric payloads', async () => {
    await context.command('/static language self de_DE', /^Static:.*de_DE\./, TIMEOUT)
    try {
      context.expect(await placeholder(context, 'blocks_broken') === '1250', 'Raw count changed under German locale')
      context.expect(await placeholder(context, 'blocks_broken_formatted') === '1.250', 'German count grouping is incorrect')
      context.expect(await placeholder(context, 'melee_damage_dealt') === '625.25', 'Raw decimal changed under German locale')
      context.expect(await placeholder(context, 'melee_damage_dealt_formatted') === '625,25', 'German decimal formatting is incorrect')
      context.expect(await placeholder(context, 'distance_flown') === '1250.5', 'Raw distance changed under German locale')
      context.expect(await placeholder(context, 'distance_flown_formatted') === '1,25 km', 'German distance formatting is incorrect')
      context.expect(await placeholder(context, 'gap_blocks_broken') === '1250', 'Raw ranking gap changed under German locale')
      context.expect(await placeholder(context, 'gap_blocks_broken_formatted') === '1.250', 'German ranking gap grouping is incorrect')
      await open(context)
      const rendered = itemText(context.bot.currentWindow.slots[0])
      context.report.staticGermanProfile = { title: text(context.bot.currentWindow.title), firstStatistic: rendered }
      context.expect(rendered.includes('1.250'), 'Profile did not display the German-formatted count', { rendered })
      await close(context)
    } finally {
      await context.command('/static language self en_US', /^Static:.*en_US\./, TIMEOUT)
    }
  })
}

export default {
  name: 'static-rankings',
  description: 'Check cached rankings, PlaceholderAPI, category/favorite profiles, localized values, and restart persistence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const baselineFile = process.env.STATIC_QA_BASELINE
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory && baselineFile, 'Set STATIC_QA_PLUGIN_DIR and STATIC_QA_BASELINE')
    context.expect(mode === 'seed' || mode === 'restart', 'STATIC_QA_MODE must be seed or restart')
    context.report.staticMode = mode
    await context.command('/staticfixture', /Fixture ready/, TIMEOUT)
    await context.command('/static language self en_US', /^Static:.*en_US\./, TIMEOUT)

    if (mode === 'restart') {
      await context.step('profile favorites, source coverage, and numeric counters survive restart', async () => {
        const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
        context.expect(JSON.stringify(await preferences(context, directory)) === JSON.stringify(FAVORITES), 'Favorites changed across restart')
        context.expect((await profile(context)).recordedSince === baseline.recordedSince, 'Profile recording date changed across restart')
        context.expect(await placeholder(context, 'blocks_broken') === '1250', 'The seeded count did not survive restart')
        await open(context)
        await context.bot.clickWindow(46, 0, 0)
        await until(context, () => statisticsItems(context).length === 8, 'Restart lost the favorite inventory entries')
        FAVORITE_LABELS.forEach((label, slot) => context.expect(itemText(context.bot.currentWindow.slots[slot]).includes(label),
          'Restart changed favorite display order', { slot, label }))
        await close(context)
      })
      return
    }

    await context.step('public API seeds stable values without changing older profile coverage', async () => {
      for (const [key, amount] of [['blocks_broken', 1250], ['melee_damage_dealt', 625.25], ['distance_flown', 1250.5]]) {
        await context.command(`/staticfixture add ${key} ${amount}`, /Fixture added/, TIMEOUT)
      }
      const current = await profile(context)
      const legacy = await profile(context, 'StaticLeader')
      context.expect(current.recordedSince > 0 && current.recordedSince <= Date.now(), 'A newly recorded profile has no valid coverage date')
      context.expect(legacy.recordedSince === 0, 'An existing profile received an invented coverage date')
      await writeFile(baselineFile, JSON.stringify(current, null, 2) + '\n')
    })

    await context.step('async rankings and cache share competition ranks, stable positions, and gaps', async () => {
      const snapshot = await ranking(context)
      const cached = parseRanking(await context.command('/staticfixture ranking blocks_broken cached', /Fixture ranking /, TIMEOUT))
      context.expect(JSON.stringify(snapshot) === JSON.stringify(cached), 'Public cache and completed ranking disagree')
      context.expect(snapshot.entries.length === 3, 'Expected the two stored profiles and the current player')
      context.expect(JSON.stringify(snapshot.entries.map(entry => entry.rank)) === '[1,1,3]', 'Tied leaders did not share competition rank')
      context.expect(JSON.stringify(snapshot.entries.map(entry => entry.position)) === '[1,2,3]', 'Ranking row positions are not stable and contiguous')
      context.expect(snapshot.entries[2].uuid === context.report.bot.uuid && snapshot.entries[2].value === 1250
        && snapshot.entries[2].gapToNext === 1250, 'Personal ranking value or next-rank gap is incorrect')
      context.report.staticRanking = snapshot
    })

    await context.step('PlaceholderAPI uses the cached board for top rows and personal rank', async () => {
      const expected = {
        rank_blocks_broken: '3', position_blocks_broken: '3', gap_blocks_broken: '1250',
        top_blocks_broken_1_name: 'StaticLeader', top_blocks_broken_2_name: 'StaticTie',
        top_blocks_broken_2_rank: '1', top_blocks_broken_3_value: '1250',
        ranking_blocks_broken_ready: 'true', ranking_blocks_broken_size: '3'
      }
      for (const [parameter, value] of Object.entries(expected)) {
        context.expect(await placeholder(context, parameter) === value, 'Placeholder differs from the cached ranking', { parameter, value })
      }
      context.expect(Number(await placeholder(context, 'ranking_blocks_broken_updated')) === context.report.staticRanking.refreshedAt,
        'PlaceholderAPI read a different ranking snapshot during the cache window')
      context.expect(await placeholder(context, 'top_blocks_broken_1_name', '--null') === 'StaticLeader',
        'Global top placeholder required a player context')
      context.expect(await placeholder(context, 'ranking_blocks_broken_size', '--null') === '3',
        'Global board placeholder required a player context')
    })

    await context.step('top command includes the requested page, personal rank, and nearby rows', async () => {
      context.report.staticRankingMessages = await capturedCommand(context, '/static top blocks_broken 1',
        [/rankings/i, /#1.*StaticLeader/, new RegExp(`#3.*${context.bot.username}`), /Your rank: #3 of 3/,
          /Gap to the next higher score: 1,250/, /Players near your position/, /Updated /])
    })

    await context.step('the highest-ranked player receives the leading-score message', async () => {
      context.report.staticLeadingMessages = await capturedCommand(context, '/static top melee_damage_dealt 1',
        [/rankings/i, new RegExp(`#1.*${context.bot.username}`), /Your rank: #1 of 3/,
          /You share or hold the highest score\./])
    })

    await context.step('profile coverage distinguishes newly recorded history from an unknown start', async () => {
      const date = await placeholder(context, 'recorded_since_formatted')
      context.expect(date.length > 0 && !/unknown/i.test(date), 'New profile formatted coverage is unknown')
      await open(context)
      const current = itemText(context.bot.currentWindow.slots[50])
      context.expect(current.includes('Recorded since:') && current.includes(date),
        'Profile coverage differs from its formatted placeholder', { current, date })
      await close(context)
      await open(context, '/static stats StaticLeader')
      context.expect(itemText(context.bot.currentWindow.slots[50]).includes('Recording start: unknown'),
        'Existing profile displays an invented recording date')
      await close(context)
    })

    await favorites(context, directory)
    await permissions(context)
    await localized(context)
    await context.step('later API increments appear after the ranking cache refreshes', async () => {
      await context.command('/staticfixture add blocks_broken 500 StaticTie', /Fixture added/, TIMEOUT)
      let updated
      await until(context, async () => {
        updated = await ranking(context)
        return updated.entries[0]?.value === 3000
      }, 'Updated public statistics did not reach the ranking cache')
      context.expect(updated.refreshedAt > context.report.staticRanking.refreshedAt,
        'The changed board retained the earlier snapshot timestamp')
      context.expect(JSON.stringify(updated.entries.map(entry => entry.rank)) === '[1,2,3]',
        'The refreshed board retained an obsolete tied rank')
      context.expect(await placeholder(context, 'top_blocks_broken_1_name') === 'StaticTie',
        'PlaceholderAPI retained the previous leaderboard winner')
      context.expect(await placeholder(context, 'top_blocks_broken_1_value') === '3000',
        'PlaceholderAPI retained the previous leader value')
      context.report.staticRefreshedRanking = updated
    })
    await context.step('explicit public flush completes before the restart boundary', async () => {
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => {
        const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
        context.expect(!response.includes('failed'), 'Statistics flush failed')
        return response.includes('complete')
      }, 'Statistics flush did not finish')
    })
  }
}
