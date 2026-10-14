import { readFile, readdir, writeFile, unlink } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const TIMEOUT = 8000

async function writeWatched(context, file, content, accepted = true) {
  if (await readFile(file, 'utf8').catch(error => {
    if (error.code === 'ENOENT') return undefined
    throw error
  }) === content) return
  const applied = context.waitForMessage(accepted
    ? /Applied configuration and language file changes/i
    : /Rejected file changes; the last known good settings remain active/i, 15000)
  await writeFile(file, content)
  await applied
}

async function until(context, predicate, message, timeout = TIMEOUT) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(50)
  }
  context.expect(false, message)
}

function componentText(component) {
  if (component === undefined || component === null) return ''
  if (typeof component === 'number' || typeof component === 'boolean') return String(component)
  if (typeof component === 'string') {
    try {
      return componentText(JSON.parse(component))
    } catch {
      return component.replace(/§[0-9a-fk-orx]/gi, '')
    }
  }
  if (Array.isArray(component)) return component.map(componentText).join('')
  if (typeof component !== 'object') return ''
  if ('type' in component && 'value' in component) return componentText(component.value)
  return componentText(component.text) + componentText(component.extra)
}

function itemText(item) {
  if (!item) return ''
  const lore = item.components?.find(component => component.type === 'lore')?.data
    ?? item.nbt?.value?.display?.value?.Lore?.value?.value ?? []
  return [componentText(item.customName), ...lore.map(componentText)].join('\n')
}

function captureWindow(context, name) {
  const window = context.bot.currentWindow
  context.report.staticWindows ??= []
  context.report.staticWindows.push({
    name,
    title: componentText(window.title),
    size: window.inventoryStart,
    slots: window.slots.slice(0, window.inventoryStart).map((item, slot) => ({
      slot,
      material: item?.name ?? null,
      text: itemText(item)
    }))
  })
}

async function openWindow(context, command) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  context.bot.chat(command)
  const [window] = await opened
  context.expect(window.inventoryStart >= 9 && window.inventoryStart <= 54,
    'Static opened an invalid menu size', { slots: window.inventoryStart })
  await until(context, () => window.slots.slice(0, window.inventoryStart).some(Boolean),
    'Menu contents did not arrive')
  await context.sleep(150)
  return window
}

async function closeWindow(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Inventory did not close')
}

async function clickTransition(context, slot) {
  const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
  await context.bot.clickWindow(slot, 0, 0)
  await opened
  await context.sleep(150)
}

function namedSlot(context, label) {
  const window = context.bot.currentWindow
  const slot = window.slots.slice(0, window.inventoryStart).findIndex(item => label.test(itemText(item)))
  context.expect(slot >= 0, `No menu item matches ${label}`)
  return slot
}

async function configurationEditing(context, directory) {
  const file = path.join(directory, 'config.toml')
  const original = await readFile(file, 'utf8')
  const autosave = Number(original.match(/^autosaveSeconds\s*=\s*(\d+)/m)?.[1])
  context.expect(Number.isFinite(autosave), 'The configuration does not contain an autosave interval')
  try {
    await context.step('configuration editor saves numeric chat input without counting it as player chat', async () => {
      const before = await statistic(context, 'chat_messages')
      await openWindow(context, '/static config')
      await clickTransition(context, namedSlot(context, /^Tracking\b/))
      const setting = namedSlot(context, /^Autosave seconds\b/)
      await context.bot.clickWindow(setting, 0, 4)
      await until(context, () => context.bot.currentWindow === null, 'Numeric configuration input did not open')
      const reopened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('17')
      await reopened
      await context.sleep(150)
      context.expect(/Current:\s*17\b/.test(itemText(context.bot.currentWindow.slots[setting])),
        'Edited autosave interval was not reflected in the menu')
      await closeWindow(context)
      context.expect(/^autosaveSeconds\s*=\s*17\b/m.test(await readFile(file, 'utf8')),
        'Edited autosave interval was not saved')
      await context.command('/static status', /Autosave:\s*17s/i, TIMEOUT)
      context.expect(await statistic(context, 'chat_messages') === before,
        'A cancelled configuration-input chat event increased the public chat counter')
    })
    await context.step('valid file edits hot reload while invalid edits retain the last good configuration', async () => {
      await writeWatched(context, file, original.replace(/^autosaveSeconds\s*=\s*\d+/m, 'autosaveSeconds = 19'))
      await until(context, async () => {
        const response = await context.command('/static status', /Autosave:\s*\d+s/i, TIMEOUT)
        return /Autosave:\s*19s/i.test(response)
      }, 'The edited file did not hot reload', 12000)
      await writeWatched(context, file, 'invalid = [', false)
      await context.command('/static status', /Autosave:\s*19s/i, TIMEOUT)
    })
  } finally {
    await writeWatched(context, file, original)
    await context.command('/static status', new RegExp(`Autosave:\\s*${autosave}s`, 'i'), TIMEOUT)
  }
}

async function englishFallback(context, directory) {
  const file = path.join(directory, 'languages', 'en_US.toml')
  const original = await readFile(file, 'utf8')
  const removed = original.replace(/^blocks_broken\s*=.*\r?\n/m, '')
  context.expect(removed !== original, 'The English catalogue does not contain the block statistic label')
  try {
    await context.step('missing language entries resolve to built-in English', async () => {
      await writeWatched(context, file, removed)
      await openWindow(context, '/stats')
      context.expect(/^Blocks broken\b/.test(itemText(context.bot.currentWindow.slots[0])),
        'A missing locale entry did not use built-in English')
      await closeWindow(context)
    })
  } finally {
    await writeWatched(context, file, original)
  }
}

async function languageEditing(context, directory) {
  const file = path.join(directory, 'languages', 'en_US.toml')
  const original = await readFile(file, 'utf8')
  try {
    await context.step('language editor saves a searched message and immediately updates the profile label', async () => {
      const before = await statistic(context, 'chat_messages')
      await openWindow(context, '/static language server edit en_US')
      await context.bot.clickWindow(49, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Language search input did not open')
      const searched = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('stat.blocks_broken')
      await searched
      await context.sleep(150)
      const slot = namedSlot(context, /stat\.blocks_broken/)
      await context.bot.clickWindow(slot, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Language message input did not open')
      const saved = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('Mined blocks')
      await saved
      await closeWindow(context)
      await openWindow(context, '/stats')
      context.expect(/^Mined blocks\b/.test(itemText(context.bot.currentWindow.slots[0])),
        'The saved language message did not update the profile')
      await closeWindow(context)
      context.expect(/^blocks_broken\s*=\s*"Mined blocks"/m.test(await readFile(file, 'utf8')),
        'The edited language entry was not persisted')
      context.expect(await statistic(context, 'chat_messages') === before,
        'Cancelled language-editor input increased the public chat counter')
    })
  } finally {
    await writeWatched(context, file, original)
  }
}

async function translatedMenus(context, directory) {
  const sourceDirectory = fileURLToPath(new URL('../../src/main/resources/languages/', import.meta.url))
  for (const [locale, label] of [['de_DE', 'Abgebaute Blöcke'], ['he_IL', 'בלוקים שנשברו']]) {
    const file = path.join(directory, 'languages', `${locale}.toml`)
    context.expect(!(await readdir(path.dirname(file))).includes(path.basename(file)),
      `The isolated translation check would overwrite ${locale}`)
    try {
      await context.step(`${locale} profile and configuration text reaches the client`, async () => {
        await writeWatched(context, file, await readFile(path.join(sourceDirectory, `${locale}.toml`), 'utf8'))
        await context.command(`/static language self ${locale}`, new RegExp(`^Static:.*${locale}\\.`), TIMEOUT)
        await openWindow(context, '/stats')
        captureWindow(context, `${locale}-profile`)
        context.expect(itemText(context.bot.currentWindow.slots[0]).startsWith(label),
          `The ${locale} statistic label was not translated`)
        await closeWindow(context)
        await openWindow(context, '/static config')
        captureWindow(context, `${locale}-configuration`)
        await closeWindow(context)
      })
    } finally {
      await closeWindow(context)
      await context.command('/static language self en_US', /your language is now en_US/i, TIMEOUT)
      const applied = context.waitForMessage(/Applied configuration and language file changes/i, 15000)
      await unlink(file)
      await applied
    }
  }
}

async function inventoryProtection(context, name) {
  const window = context.bot.currentWindow
  const filler = window.slots.slice(0, window.inventoryStart)
    .findIndex(item => item?.name === 'black_stained_glass_pane')
  context.expect(filler >= 0, `${name} has no protected background slot`)
  await context.bot.clickWindow(filler, 0, 0)
  await context.sleep(200)
  context.expect(window.selectedItem === null, `${name} allowed taking its background item`)
  await context.bot.clickWindow(filler, 0, 1)
  await context.sleep(200)
  context.expect(window.selectedItem === null, `${name} left a cursor item after shift-click`)
  const stoneSlot = window.slots.findIndex((item, index) =>
    index >= window.inventoryStart && item?.name === 'stone')
  context.expect(stoneSlot >= 0, `${name} lost the player's stone`)
  await context.bot.clickWindow(stoneSlot, 0, 1)
  await context.sleep(200)
  context.expect(window.slots[stoneSlot]?.name === 'stone' && window.slots[stoneSlot]?.count === 1,
    `${name} accepted a player inventory shift-click`)
  const inventory = context.bot.inventory.items()
  context.expect(inventory.length === 1 && inventory[0].name === 'stone' && inventory[0].count === 1,
    `${name} leaked or duplicated items`, { items: inventory.map(item => ({ name: item.name, count: item.count })) })
}

async function statistic(context, key) {
  const response = await context.command(`/staticfixture value ${key}`,
    new RegExp(`Fixture value ${key} ([0-9.e+-]+)`), TIMEOUT)
  const value = Number(response.match(new RegExp(`Fixture value ${key} ([0-9.e+-]+)`))[1])
  context.expect(Number.isFinite(value), `Invalid ${key} value`, { response })
  return value
}

async function fixtureCounts(context) {
  const response = await context.command('/staticfixture counts', /Fixture cancellations \d+ \d+ \d+/, TIMEOUT)
  return response.match(/Fixture cancellations (\d+) (\d+) (\d+)/).slice(1).map(Number)
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'Statistics durable write failed')
    return response.includes('complete')
  }, 'Statistics durable write did not complete')
}

async function storedPlayer(context) {
  const directory = process.env.STATIC_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set STATIC_QA_PLUGIN_DIR to the isolated instance Static data folder')
  const source = JSON.parse(await readFile(path.join(directory, 'storage', 'players.json'), 'utf8'))
  context.expect(source.version === 1, 'Unexpected persisted statistics format')
  const player = source.players.find(entry => entry.name === context.bot.username)
  context.expect(player !== undefined, 'The connected player was not persisted')
  return player
}

async function setBlock(context, position, material) {
  await context.command(`/setblock ${position.x} ${position.y} ${position.z} ${material}`,
    /changed|set the block|could not set/i, TIMEOUT)
  await until(context, () => context.bot.blockAt(position)?.name === material,
    `Expected ${material} at ${position}`)
}

async function setMode(context, mode) {
  if (context.bot.game.gameMode === mode) return
  await context.command(`/gamemode ${mode}`, new RegExp(mode, 'i'), TIMEOUT)
  await until(context, () => context.bot.game.gameMode === mode, `Expected ${mode} game mode`)
  await context.sleep(100)
}

async function actionChecks(context) {
  await context.command('/staticfixture', /Fixture ready/, TIMEOUT)
  await setMode(context, 'survival')
  await context.command('/give @s diamond_pickaxe 1', /given|gave/i, TIMEOUT)
  await until(context, () => context.bot.inventory.items().some(item => item.name === 'diamond_pickaxe'),
    'Mining tool did not arrive')
  await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'diamond_pickaxe'), 'hand')
  const target = context.bot.entity.position.floored().offset(2, 0, 0)
  const ground = target.offset(0, -1, 0)
  const baseline = {
    blocks_broken: await statistic(context, 'blocks_broken'),
    blocks_placed: await statistic(context, 'blocks_placed'),
    chat_messages: await statistic(context, 'chat_messages')
  }
  await context.step('real survival block breaking increases the recorded counter', async () => {
    await setBlock(context, target, 'stone')
    await context.bot.dig(context.bot.blockAt(target))
    await until(context, () => context.bot.blockAt(target)?.name === 'air', 'Survival block did not break')
    context.expect(await statistic(context, 'blocks_broken') === baseline.blocks_broken + 1,
      'Survival block break was not counted exactly once')
  })
  await context.step('cancelled block breaking leaves the counter unchanged', async () => {
    await setBlock(context, target, 'stone')
    const before = await fixtureCounts(context)
    await context.command('/staticfixture cancel on', /Fixture cancellation on/, TIMEOUT)
    await context.bot.dig(context.bot.blockAt(target))
    await context.sleep(250)
    const after = await fixtureCounts(context)
    context.expect(after[0] === before[0] + 1, 'The cancellation fixture did not observe the attempted break')
    context.expect(context.bot.blockAt(target)?.name === 'stone', 'Cancelled block break changed the world')
    context.expect(await statistic(context, 'blocks_broken') === baseline.blocks_broken + 1,
      'Static counted a cancelled block break')
    await context.command('/staticfixture cancel off', /Fixture cancellation off/, TIMEOUT)
  })
  await context.step('creative block breaking is excluded by the default configuration', async () => {
    await setMode(context, 'creative')
    await context.bot.dig(context.bot.blockAt(target))
    await until(context, () => context.bot.blockAt(target)?.name === 'air', 'Creative block did not break')
    context.expect(await statistic(context, 'blocks_broken') === baseline.blocks_broken + 1,
      'Static counted creative block breaking while disabled')
    await setMode(context, 'survival')
  })
  await context.step('real survival block placement increases the recorded counter', async () => {
    await setBlock(context, ground, 'stone')
    await context.command('/give @s dirt 4', /given|gave/i, TIMEOUT)
    await until(context, () => context.bot.inventory.items().some(item => item.name === 'dirt'),
      'Placement blocks did not arrive')
    await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'dirt'), 'hand')
    await context.bot.placeBlock(context.bot.blockAt(ground), ground.offset(0, 1, 0).minus(ground))
    await until(context, () => context.bot.blockAt(target)?.name === 'dirt', 'Survival block was not placed')
    context.expect(await statistic(context, 'blocks_placed') === baseline.blocks_placed + 1,
      'Survival placement was not counted exactly once')
  })
  await context.step('cancelled block placement leaves the world and counter unchanged', async () => {
    await setBlock(context, target, 'air')
    const before = await fixtureCounts(context)
    await context.command('/staticfixture cancel on', /Fixture cancellation on/, TIMEOUT)
    try {
      await context.bot.placeBlock(context.bot.blockAt(ground), ground.offset(0, 1, 0).minus(ground))
    } catch (error) {
      context.report.staticCancelledPlacementResponse = String(error)
    }
    await context.sleep(250)
    const after = await fixtureCounts(context)
    context.expect(after[1] === before[1] + 1, 'The cancellation fixture did not observe the attempted placement')
    context.expect(context.bot.blockAt(target)?.name === 'air', 'Cancelled placement changed the world')
    context.expect(await statistic(context, 'blocks_placed') === baseline.blocks_placed + 1,
      'Static counted a cancelled block placement')
    await context.command('/staticfixture cancel off', /Fixture cancellation off/, TIMEOUT)
  })
  await context.step('accepted chat increments once and cancelled chat is excluded', async () => {
    await context.command('STATIC_ACCEPTED_CHAT', /STATIC_ACCEPTED_CHAT/, TIMEOUT)
    context.expect(await statistic(context, 'chat_messages') === baseline.chat_messages + 1,
      'Accepted player chat was not counted exactly once')
    const before = await fixtureCounts(context)
    await context.command('/staticfixture cancel on', /Fixture cancellation on/, TIMEOUT)
    context.bot.chat('STATIC_CANCEL_CHAT')
    await context.sleep(350)
    const after = await fixtureCounts(context)
    context.expect(after[2] === before[2] + 1, 'The cancellation fixture did not observe the chat event')
    context.expect(await statistic(context, 'chat_messages') === baseline.chat_messages + 1,
      'Static counted a cancelled chat event')
    await context.command('/staticfixture cancel off', /Fixture cancellation off/, TIMEOUT)
  })
  await context.step('durable JSON stores the same action counters returned by the public API', async () => {
    await flush(context)
    const player = await storedPlayer(context)
    for (const key of Object.keys(baseline)) {
      context.expect(player.values[key] === baseline[key] + 1,
        `Persisted ${key} does not match the accepted actions`, { values: player.values })
    }
    context.report.staticPersistedPlayer = player
  })
}

async function persistenceChecks(context) {
  await context.step('server restart restores prior action counters and records the next join', async () => {
    const player = await storedPlayer(context)
    for (const key of ['blocks_broken', 'blocks_placed', 'chat_messages']) {
      context.expect(player.values[key] >= 1, `No previous ${key} action was stored`)
      context.expect(await statistic(context, key) === player.values[key], `Restart changed ${key}`)
    }
    context.expect(await statistic(context, 'joins') === player.values.joins + 1,
      'Restart did not preserve earlier joins and record the new connection')
    const onlineTime = await statistic(context, 'online_time')
    const currentConnectionMillis = Date.now() - Date.parse(context.report.startedAt)
    context.expect(onlineTime >= player.values.online_time
      && onlineTime - player.values.online_time <= currentConnectionMillis + 5000,
    'The restored online-time total includes time spent disconnected',
    { stored: player.values.online_time, current: onlineTime, currentConnectionMillis })
    await context.sleep(400)
    context.expect(await statistic(context, 'online_time') > onlineTime,
      'Online time did not advance during the new active session')
    context.report.staticRestoredPlayer = player
    await openWindow(context, '/stats')
    captureWindow(context, 'restored-profile')
    await closeWindow(context)
  })
}

async function ordinaryPlayerChecks(context) {
  for (const command of ['/static status', '/static config', '/static debug dump upload=false',
    '/static language server edit en_US']) {
    await context.step(`${command} rejects a player without administrative permission`, async () => {
      await context.command(command, /permission|not permitted|not allowed/i, TIMEOUT)
      context.expect(context.bot.currentWindow === null, 'An unauthorized administrative menu opened')
    })
  }
  await context.step('ordinary players can open their statistics', async () => {
    const window = await openWindow(context, '/stats')
    captureWindow(context, 'ordinary-player-profile')
    await context.bot.clickWindow(0, 0, 1)
    await context.sleep(150)
    context.expect(window.selectedItem === null && context.bot.inventory.items().length === 0,
      'An ordinary player could take a profile control into their inventory')
    await closeWindow(context)
  })
  await context.step('ordinary players can inspect an offline profile with the default permission', async () => {
    context.expect(context.bot.players.StaticQA === undefined, 'The reference profile is unexpectedly online')
    const window = await openWindow(context, '/static stats StaticQA')
    context.expect(componentText(window.title).includes('StaticQA'), 'The offline profile did not identify its owner')
    context.expect(/Blocks broken:\s*[1-9]\d*/.test(itemText(window.slots[0])),
      'The offline profile did not show its recorded block statistic')
    captureWindow(context, 'offline-profile')
    await closeWindow(context)
  })
  await context.step('ordinary players can choose their personal English language', async () => {
    await context.command('/static language self en_US', /your language is now en_US/i, TIMEOUT)
  })
}

async function administratorChecks(context) {
  const directory = process.env.STATIC_QA_PLUGIN_DIR
  context.expect(directory !== undefined, 'Set STATIC_QA_PLUGIN_DIR to the isolated instance Static data folder')
  await context.step('startup generates only the English language file', async () => {
    const entries = await readdir(path.join(directory, 'languages'), { withFileTypes: true })
    const locales = entries.filter(entry => entry.isFile() && entry.name.endsWith('.toml')).map(entry => entry.name)
    context.expect(locales.length === 1 && locales[0] === 'en_US.toml',
      'Startup created non-English language files', { locales })
  })
  await context.step('Director help and runtime status are available', async () => {
    await context.command('/static', /stats|statistics|configuration/i, TIMEOUT)
    await context.command('/static status', /Tracking:/i, TIMEOUT)
  })
  await context.step('operator statistics and rankings respond', async () => {
    await context.command('/static top joins', new RegExp(context.bot.username, 'i'), TIMEOUT)
  })
  await context.step('profile menu opens through the stats shortcut', async () => {
    const window = await openWindow(context, '/stats')
    context.expect(componentText(window.title).includes('Static') && componentText(window.title).includes(context.bot.username),
      'Profile title does not identify Static and the selected player', { title: componentText(window.title) })
    captureWindow(context, 'profile')
    context.expect(window.slots[0]?.name === 'iron_pickaxe' && /Blocks broken/.test(itemText(window.slots[0])),
      'The profile does not show the first statistic')
    context.expect(window.slots[49]?.name === 'clock' && window.slots[53]?.name === 'barrier',
      'The profile is missing refresh or close controls')
    const rankings = context.waitForMessage(/Blocks broken rankings/i, TIMEOUT)
    await context.bot.clickWindow(0, 0, 0)
    await rankings
    context.expect(window.selectedItem === null, 'The profile allowed taking a statistic item')
    const refreshed = context.waitForEvent('windowOpen', () => true, TIMEOUT)
    await context.bot.clickWindow(49, 0, 0)
    await refreshed
    await context.sleep(150)
    await context.bot.clickWindow(53, 0, 0)
    await until(context, () => context.bot.currentWindow === null, 'The profile Close control did not close its inventory')
    await openWindow(context, `/static stats ${context.bot.username}`)
    await closeWindow(context)
  })
  await context.step('unknown player and statistic input receives a bounded error', async () => {
    await context.command('/static stats MissingStaticQA', /No recorded player matches/i, TIMEOUT)
    await context.command('/static top missing_static_stat', /Unknown statistic/i, TIMEOUT)
    await context.command('/static top joins page=999999', /outside the available rankings/i, TIMEOUT)
  })
  await context.step('configuration menu protects its controls and player inventory', async () => {
    await context.command('/clear', /removed|no items/i, TIMEOUT)
    await setMode(context, 'survival')
    await context.command('/give @s stone 1', /given|gave/i, TIMEOUT)
    await until(context, () => context.bot.inventory.items().some(item => item.name === 'stone'),
      'Test stone did not arrive')
    await openWindow(context, '/static config')
    captureWindow(context, 'configuration')
    await inventoryProtection(context, 'Configuration')
    await closeWindow(context)
  })
  await context.step('language editor opens the English catalogue', async () => {
    await openWindow(context, '/static language server edit en_US')
    captureWindow(context, 'english-editor')
    await inventoryProtection(context, 'Language editor')
    await closeWindow(context)
  })
  await context.step('personal language selection accepts English', async () => {
    await context.command('/static language self en_US', /your language is now en_US/i, TIMEOUT)
  })
  await configurationEditing(context, directory)
  await languageEditing(context, directory)
  await englishFallback(context, directory)
  await translatedMenus(context, directory)
  await actionChecks(context)
  await context.step('commands remain available after configuration and language hot reload', async () => {
    await context.command('/static status', /Tracking:/i, TIMEOUT)
  })
  await context.step('local diagnostic dump is written', async () => {
    await context.command('/static debug dump upload=false', /saved|written|local report/i, TIMEOUT)
  })
}

export default {
  name: 'static-acceptance',
  description: 'Verify Static commands, languages, menus, accepted and cancelled statistics, durable storage, and diagnostics.',
  async run(context) {
    context.expect(context.bot.health > 0, 'Statistics client must be alive')
    if (process.env.STATIC_QA_MODE === 'permissions') {
      await ordinaryPlayerChecks(context)
    } else if (process.env.STATIC_QA_MODE === 'persistence') {
      await persistenceChecks(context)
    } else if (process.env.STATIC_QA_MODE === 'actions') {
      await actionChecks(context)
    } else if (process.env.STATIC_QA_MODE === 'projectiles') {
      const scenario = (await import('./static-projectiles.mjs')).default
      await scenario.run(context)
    } else {
      await administratorChecks(context)
    }
    context.expect(context.bot.player !== undefined, 'Statistics client disconnected before acceptance finished')
  }
}
