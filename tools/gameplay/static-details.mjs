import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 12000
const KEYS = ['mined_diamond_ore', 'placed_oak_planks', 'killed_zombie', 'caught_items', 'caught_cod', 'bred_animals', 'bred_cow', 'crafted_items', 'crafted_oak_planks', 'traded_items', 'traded_bread']

async function until(context, predicate, message, timeout = TIMEOUT) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function detail(context, key) {
  const response = await context.command(`/staticfixture detail ${key}`, new RegExp(`^Fixture detail ${key} `), TIMEOUT)
  const [value, since] = response.split(' ').slice(3).map(Number)
  return { value, since }
}

async function expectValue(context, key, value) {
  await until(context, async () => (await detail(context, key)).value === value, `${key} did not reach ${value}`)
}

function configuration(enabled = true, keys = KEYS) {
  return `[tracking]\nautosaveSeconds = 10\ntrackCreative = false\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[details]\nenabled = ${enabled}\nselectedKeys = ${JSON.stringify(keys)}\n`
}

async function reload(context, file, enabled = true, keys = KEYS) {
  const feedback = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(file, configuration(enabled, keys))
  await feedback
}

async function mode(context, value) {
  if (context.bot.game.gameMode === value) return
  context.bot.chat(`/gamemode ${value}`)
  await until(context, () => context.bot.game.gameMode === value, `Game mode did not become ${value}`)
}

function windowState(context) {
  const window = context.bot.currentWindow
  return window ? { id: window.id, type: window.type, title: window.title, inventoryStart: window.inventoryStart,
    cursor: window.selectedItem?.name ?? null } : null
}

function guiTrace(context, phase, action) {
  context.report.guiTransitions ??= []
  context.report.guiTransitions.push({ at: new Date().toISOString(), phase, ...action, window: windowState(context) })
}

async function open(context, command) {
  guiTrace(context, 'command-start', { command })
  try {
    const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
    context.bot.chat(command)
    await opened
    guiTrace(context, 'command-window-opened', { command })
    await until(context, () => context.bot.currentWindow?.slots[49], `Menu contents did not arrive for ${command}`)
    guiTrace(context, 'command-ready', { command })
  } catch (error) {
    guiTrace(context, 'command-failed', { command, error: error.message })
    throw new Error(`Opening ${command} failed; window=${JSON.stringify(windowState(context))}: ${error.message}`, { cause: error })
  }
}

async function transition(context, slot) {
  const from = windowState(context)
  const clicked = context.bot.currentWindow?.slots[slot]?.name ?? null
  guiTrace(context, 'click-start', { slot, clicked })
  try {
    const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
    await context.bot.clickWindow(slot, 0, 0)
    guiTrace(context, 'click-sent', { slot })
    await opened
    guiTrace(context, 'click-window-opened', { slot })
    await until(context, () => context.bot.currentWindow?.slots[49], `Menu contents did not arrive after slot ${slot}`)
    guiTrace(context, 'click-ready', { slot })
  } catch (error) {
    guiTrace(context, 'click-failed', { slot, error: error.message })
    throw new Error(`Menu slot ${slot} (${clicked}) failed from ${JSON.stringify(from)}; window=${JSON.stringify(windowState(context))}: ${error.message}`, { cause: error })
  }
}

async function close(context) {
  if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
  await until(context, () => context.bot.currentWindow === null, 'Menu did not close')
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Personal inventory did not synchronize after menu close')), TIMEOUT)
    context.bot._syncWindow(context.bot.inventory).then(() => {
      clearTimeout(timeout)
      resolve()
    }, error => {
      clearTimeout(timeout)
      reject(error)
    })
  })
}

export default {
  name: 'static-details',
  description: 'Verify detailed lifetime quantities, exclusions, menus, retained selection, and restart persistence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    context.expect(Boolean(directory), 'STATIC_QA_PLUGIN_DIR is required')
    const file = path.join(directory, 'config.toml')
    const baselineFile = path.join(path.dirname(directory), 'StaticFixture', 'details-baseline.json')
    const phase = process.env.STATIC_QA_MODE ?? 'seed'
    context.report.staticMode = phase
    if (phase === 'restart') {
      await context.step('restart retains exact detailed values and first observation times', async () => {
        const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
        for (const [key, expected] of Object.entries(baseline)) {
          const actual = await detail(context, key)
          context.expect(JSON.stringify(actual) === JSON.stringify(expected), `Restart changed ${key}`, { expected, actual })
        }
        context.report.details = baseline
      })
      await context.step('detailed profile remains available after restart', async () => {
        await open(context, '/static details')
        context.expect(context.bot.currentWindow.inventoryStart === 54, 'Details menu size changed')
        await close(context)
      })
      return
    }
    await reload(context, file)
    await mode(context, 'survival')
    const target = context.bot.entity.position.floored().offset(1, 0, 0)
    const setBlock = async material => {
      context.bot.chat(`/setblock ${target.x} ${target.y} ${target.z} minecraft:${material}`)
      await until(context, () => context.bot.blockAt(target)?.name === material, `Missing test ${material}`)
    }
    await context.step('survival ore breaking counts once, cancelled and creative breaks do not count', async () => {
      context.bot.chat('/give @s minecraft:netherite_pickaxe')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'netherite_pickaxe'), 'Pickaxe missing')
      await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'netherite_pickaxe'), 'hand')
      await setBlock('diamond_ore')
      await context.bot.dig(context.bot.blockAt(target), true)
      await expectValue(context, 'mined_diamond_ore', 1)
      await setBlock('diamond_ore')
      await context.command('/staticfixture cancel on', /Fixture cancellation on/, TIMEOUT)
      await context.bot.dig(context.bot.blockAt(target), true)
      await context.sleep(250)
      context.expect((await detail(context, 'mined_diamond_ore')).value === 1, 'Cancelled break counted')
      context.expect(context.bot.blockAt(target)?.name === 'diamond_ore', 'Cancelled break changed world')
      await context.command('/staticfixture cancel off', /Fixture cancellation off/, TIMEOUT)
      await mode(context, 'creative')
      await context.bot.dig(context.bot.blockAt(target), true)
      context.expect((await detail(context, 'mined_diamond_ore')).value === 1, 'Creative break counted')
      await mode(context, 'survival')
    })
    await context.step('placement counts the selected material', async () => {
      const target = context.bot.entity.position.floored().offset(2, 0, 0)
      const ground = target.offset(0, -1, 0)
      const inventory = context.bot.inventory
      const synchronize = async () => {
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => reject(new Error('Placement inventory synchronization timed out')), TIMEOUT)
          context.bot._syncWindow(inventory).then(() => {
            clearTimeout(timeout)
            resolve()
          }, error => {
            clearTimeout(timeout)
            reject(error)
          })
        })
      }
      await context.command('/execute if entity @s[gamemode=survival] run tellraw @s "Placement survival confirmed"', /Placement survival confirmed/, TIMEOUT)
      await context.command(`/setblock ${ground.x} ${ground.y} ${ground.z} minecraft:stone`, /changed|could not set|already/i, TIMEOUT)
      await context.command(`/setblock ${target.x} ${target.y} ${target.z} minecraft:air`, /changed|could not set|already/i, TIMEOUT)
      await context.command(`/execute if block ${target.x} ${target.y} ${target.z} minecraft:air run tellraw @s "Placement target clear"`, /Placement target clear/, TIMEOUT)
      await context.command(`/execute if block ${ground.x} ${ground.y} ${ground.z} minecraft:stone run tellraw @s "Placement support confirmed"`, /Placement support confirmed/, TIMEOUT)
      await until(context, () => context.bot.blockAt(target)?.name === 'air', 'Placement target is not visibly empty')
      await until(context, () => context.bot.blockAt(ground)?.name === 'stone', 'Placement support did not arrive')
      context.bot.chat('/give @s minecraft:oak_planks 2')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'oak_planks' && item.count === 2), 'Two planks missing')
      await synchronize()
      await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'oak_planks'), 'hand')
      await synchronize()
      context.expect(context.bot.heldItem?.name === 'oak_planks' && !inventory.selectedItem, 'Planks were not equipped with an empty cursor')
      await context.command('/execute if items entity @s weapon.mainhand minecraft:oak_planks run tellraw @s "Placement item confirmed"', /Placement item confirmed/, TIMEOUT)
      const beforePlanks = inventory.items().reduce((count, item) => count + (item.name === 'oak_planks' ? item.count : 0), 0)
      const support = context.bot.blockAt(ground)
      context.expect(support?.boundingBox === 'block', 'Placement support is not a solid block')
      await context.bot.placeBlock(support, target.minus(ground))
      await until(context, () => context.bot.blockAt(target)?.name === 'oak_planks', 'Placement packet did not produce oak planks')
      await context.command(`/execute if block ${target.x} ${target.y} ${target.z} minecraft:oak_planks run tellraw @s "Placement block confirmed"`, /Placement block confirmed/, TIMEOUT)
      await synchronize()
      const afterPlanks = inventory.items().reduce((count, item) => count + (item.name === 'oak_planks' ? item.count : 0), 0)
      context.expect(afterPlanks === beforePlanks - 1, 'Placement did not consume exactly one plank', { beforePlanks, afterPlanks })
      context.report.placement = { target: { x: target.x, y: target.y, z: target.z }, beforePlanks, afterPlanks }
      await expectValue(context, 'placed_oak_planks', 1)
    })
    await context.step('a player-credited entity kill counts the selected mob', async () => {
      await context.command('/difficulty normal', /difficulty.*Normal/i, TIMEOUT)
      await context.command('/time set night', /Set .*time/i, TIMEOUT)
      context.bot.chat('/give @s minecraft:netherite_sword')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'netherite_sword'), 'Sword missing')
      await context.bot.equip(context.bot.inventory.items().find(item => item.name === 'netherite_sword'), 'hand')
      await context.sleep(600)
      context.bot.chat('/summon minecraft:zombie ~ ~ ~2 {NoAI:1b,PersistenceRequired:1b,Health:1.0f}')
      await until(context, () => Object.values(context.bot.entities).some(entity => entity.name === 'zombie'), 'Zombie missing')
      const zombie = context.bot.nearestEntity(entity => entity.name === 'zombie')
      await until(context, async () => {
        if ((await detail(context, 'killed_zombie')).value === 1) return true
        if (context.bot.entities[zombie.id]) context.bot.attack(zombie)
        await context.sleep(650)
        return false
      }, 'Player-credited zombie kill did not count')
    })
    await context.step('crafting counts output items rather than recipe clicks', async () => {
      context.bot.chat('/give @s minecraft:oak_log 3')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'oak_log' && item.count === 3), 'Three logs missing')
      const inventory = context.bot.inventory
      const synchronize = async () => {
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => reject(new Error('Crafting inventory synchronization timed out')), TIMEOUT)
          context.bot._syncWindow(inventory).then(() => {
            clearTimeout(timeout)
            resolve()
          }, error => {
            clearTimeout(timeout)
            reject(error)
          })
        })
      }
      const count = name => inventory.items().reduce((total, item) => total + (item.name === name ? item.count : 0), 0)
      await synchronize()
      const beforePlanks = count('oak_planks')
      const beforeLogs = count('oak_log')
      const logs = inventory.items().find(item => item.name === 'oak_log' && item.count === 3)
      context.expect(Boolean(logs) && !inventory.selectedItem && !inventory.slots[1], 'Crafting grid or cursor was not empty')
      await context.bot.clickWindow(logs.slot, 0, 0)
      await synchronize()
      context.expect(inventory.selectedItem?.name === 'oak_log' && inventory.selectedItem.count === 3, 'Logs were not picked up')
      await context.bot.clickWindow(1, 0, 0)
      await synchronize()
      context.expect(inventory.slots[1]?.count === 3 && inventory.slots[0]?.name === 'oak_planks'
        && inventory.slots[0].count === 4 && !inventory.selectedItem, 'Crafting recipe output was not confirmed by the server')
      await context.bot.clickWindow(0, 0, 1)
      await synchronize()
      context.expect(count('oak_planks') === beforePlanks + 12 && count('oak_log') === beforeLogs - 3
        && !inventory.slots[1] && !inventory.selectedItem, 'Bulk crafting did not consume three logs and deliver twelve planks', {
        beforePlanks, afterPlanks: count('oak_planks'), beforeLogs, afterLogs: count('oak_log')
      })
      context.report.crafting = { beforePlanks, afterPlanks: count('oak_planks'), consumedLogs: beforeLogs - count('oak_log') }
      await expectValue(context, 'crafted_items', 12)
      await expectValue(context, 'crafted_oak_planks', 12)
    })
    await context.step('real villager trading counts purchased items without counting them as crafts', async () => {
      const synchronize = async window => {
        await new Promise((resolve, reject) => {
          const timeout = setTimeout(() => reject(new Error('Trade inventory synchronization timed out')), TIMEOUT)
          context.bot._syncWindow(window).then(() => {
            clearTimeout(timeout)
            resolve()
          }, error => {
            clearTimeout(timeout)
            reject(error)
          })
        })
      }
      const breadCount = window => window.slots.slice(window.inventoryStart, window.inventoryEnd)
        .reduce((count, item) => count + (item?.name === 'bread' ? item.count : 0), 0)
      context.bot.chat('/give @s minecraft:emerald 3')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'emerald'), 'Emeralds missing')
      context.bot.chat('/summon villager ~ ~ ~2 {NoAI:1b,Invulnerable:1b,VillagerData:{profession:farmer,level:2,type:plains},Offers:{Recipes:[{buy:{id:emerald,count:1},sell:{id:bread,count:3},maxUses:100,uses:0,priceMultiplier:0.0f}]}}')
      await until(context, () => Object.values(context.bot.entities).some(entity => entity.name === 'villager'), 'Villager missing')
      const villager = context.bot.nearestEntity(entity => entity.name === 'villager')
      const merchant = await context.bot.openVillager(villager)
      await synchronize(merchant)
      const beforeNormalBread = breadCount(merchant)
      await merchant.trade(0, 3)
      await synchronize(merchant)
      const normalItems = breadCount(merchant) - beforeNormalBread
      context.expect(normalItems === 9 && !merchant.selectedItem, 'Normal trades did not deliver nine bread items', { normalItems })
      merchant.close()
      await synchronize(context.bot.inventory)
      await expectValue(context, 'traded_items', 9)
      await expectValue(context, 'traded_bread', 9)
      context.expect((await detail(context, 'crafted_items')).value === 12, 'Purchased items entered crafting totals')
      context.bot.chat('/give @s minecraft:emerald 2')
      await until(context, () => context.bot.inventory.items().some(item => item.name === 'emerald' && item.count >= 2), 'Shift-trade emeralds missing')
      const shiftMerchant = await context.bot.openVillager(villager)
      await synchronize(shiftMerchant)
      const beforeBread = breadCount(shiftMerchant)
      shiftMerchant.selectedTrade = shiftMerchant.trades[0]
      context.bot._client.write('select_trade', { slot: 0 })
      await until(context, () => shiftMerchant.slots[0]?.count === 2 && shiftMerchant.slots[2]?.name === 'bread', 'Shift-trade inputs or output missing')
      await context.bot.clickWindow(2, 0, 1)
      await synchronize(shiftMerchant)
      await until(context, () => breadCount(shiftMerchant) === beforeBread + 6 && !shiftMerchant.slots[0], 'Shift trade did not deliver both recipe outputs')
      context.report.trading = { normalItems, shiftItems: breadCount(shiftMerchant) - beforeBread }
      shiftMerchant.close()
      await synchronize(context.bot.inventory)
      await expectValue(context, 'traded_items', 15)
      await expectValue(context, 'traded_bread', 15)
      context.expect((await detail(context, 'crafted_items')).value === 12, 'Shift-traded items entered crafting totals')
    })
    await context.step('profile links, category controls, and configuration section are accessible', async () => {
      await open(context, '/stats')
      await transition(context, 50)
      context.expect(context.bot.currentWindow.inventoryStart === 54, 'Profile did not open details')
      await transition(context, 45)
      await transition(context, 46)
      await transition(context, 51)
      await close(context)
      await open(context, '/static config')
      await transition(context, 12)
      context.expect(context.bot.currentWindow.slots[0] && context.bot.currentWindow.slots[1], 'Detailed settings missing')
      await context.bot.clickWindow(1, 0, 0)
      await until(context, () => context.bot.currentWindow === null, 'Detail selection prompt did not open')
      guiTrace(context, 'selection-prompt-submit', { value: '+traded_emerald' })
      const edited = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('+traded_emerald')
      try {
        await edited
      } catch (error) {
        guiTrace(context, 'selection-prompt-failed', { error: error.message })
        throw new Error(`Detailed selection prompt did not reopen: ${error.message}`, { cause: error })
      }
      guiTrace(context, 'selection-prompt-opened', {})
      context.expect((await readFile(file, 'utf8')).includes('traded_emerald'), 'Incremental selection edit did not save')
      await close(context)
    })
    await context.step('disabled and unselected counters retain values and resume without backfill', async () => {
      const before = await detail(context, 'mined_diamond_ore')
      await reload(context, file, false)
      await context.command('/staticfixture detail-add mined_diamond_ore 7', /Fixture detail added/, TIMEOUT)
      context.expect((await detail(context, 'mined_diamond_ore')).value === before.value, 'Disabled collection changed a value')
      await reload(context, file, true, KEYS.filter(key => key !== 'mined_diamond_ore'))
      await context.command('/staticfixture detail-add mined_diamond_ore 7', /Fixture detail added/, TIMEOUT)
      context.expect((await detail(context, 'mined_diamond_ore')).value === before.value, 'Unselected counter changed')
      await reload(context, file)
      await context.command('/staticfixture detail-add mined_diamond_ore 2', /Fixture detail added/, TIMEOUT)
      const after = await detail(context, 'mined_diamond_ore')
      context.expect(after.value === before.value + 2 && after.since === before.since, 'Resume backfilled or reset coverage')
    })
    await context.step('detail permissions protect other profiles and do not stop collection', async () => {
      const peer = '00000000-0000-0000-0000-000000000093'
      await context.command(`/staticfixture add-uuid ${peer} blocks_broken 1`, /Fixture UUID added/, TIMEOUT)
      await context.command('/staticfixture permission static.details.others false', /Fixture permission/, TIMEOUT)
      await context.command(`/static details ${peer}`, /permission/i, TIMEOUT)
      await context.command('/staticfixture permission static.details.others true', /Fixture permission/, TIMEOUT)
      await context.command('/staticfixture permission static.details false', /Fixture permission/, TIMEOUT)
      await context.command('/static details', /permission/i, TIMEOUT)
      await context.command('/staticfixture detail-add mined_diamond_ore 1', /Fixture detail added/, TIMEOUT)
      await expectValue(context, 'mined_diamond_ore', 4)
      await context.command('/staticfixture permission static.details true', /Fixture permission/, TIMEOUT)
    })
    await context.step('detailed totals and timestamps flush atomically with the profile', async () => {
      const baseline = {}
      for (const key of KEYS) baseline[key] = await detail(context, key)
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => (await context.command('/staticfixture saved', /Fixture flush /, TIMEOUT)).endsWith('complete'), 'Flush incomplete')
      await mkdir(path.dirname(baselineFile), { recursive: true })
      await writeFile(baselineFile, JSON.stringify(baseline, null, 2))
      context.report.details = baseline
    })
  }
}
