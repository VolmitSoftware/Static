import { readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'

const TIMEOUT = 8000

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (predicate()) return
    await context.sleep(50)
  }
  context.expect(false, message)
}

function event(bot, name, timeout = TIMEOUT) {
  return new Promise((resolve, reject) => {
    const received = (...arguments_) => {
      clearTimeout(timer)
      resolve(arguments_)
    }
    const timer = setTimeout(() => {
      bot.removeListener(name, received)
      reject(new Error(`Timed out waiting for secondary client ${name}`))
    }, timeout)
    bot.once(name, received)
  })
}

async function value(context, key, target) {
  const response = await context.command(`/staticfixture value ${key}${target ? ` ${target}` : ''}`,
    new RegExp(`Fixture value ${key} ([0-9.e+-]+)`), TIMEOUT)
  return Number(response.match(new RegExp(`Fixture value ${key} ([0-9.e+-]+)`))[1])
}

async function mode(context, bot, selected, username) {
  if (bot.game.gameMode === selected) return
  await context.command(`/gamemode ${selected}${username ? ` ${username}` : ''}`,
    /game mode|mode for/i, TIMEOUT)
  await until(context, () => bot.game.gameMode === selected, `Secondary game mode did not become ${selected}`)
}

export default {
  name: 'static-projectile-filter-transitions',
  description: 'Dispatch synthetic piercing-arrow events with real connected players to verify eligibility transitions.',
  async run(context) {
    const packageFile = process.env.STATIC_QA_MINEFLAYER_PACKAGE
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    context.expect(packageFile !== undefined, 'Set STATIC_QA_MINEFLAYER_PACKAGE to the installed harness package.json')
    context.expect(directory !== undefined, 'Set STATIC_QA_PLUGIN_DIR to the isolated instance Static data folder')
    const mineflayer = createRequire(packageFile)('mineflayer')
    const configFile = path.join(directory, 'config.toml')
    const originalConfig = await readFile(configFile, 'utf8')
    const targetName = 'StaticTarget'
    const target = mineflayer.createBot({
      host: context.server.host,
      port: context.server.port,
      username: targetName,
      auth: 'offline',
      version: context.bot.version
    })
    let expectedEnd = false
    let targetFailure
    const failed = error => { targetFailure = error instanceof Error ? error : new Error(String(error)) }
    const kicked = reason => failed(`Secondary client kicked: ${JSON.stringify(reason)}`)
    const ended = reason => { if (!expectedEnd) failed(`Secondary client disconnected: ${reason}`) }
    target.on('error', failed)
    target.on('kicked', kicked)
    target.on('end', ended)
    const healthy = () => context.expect(targetFailure === undefined, 'Secondary client failed',
      { reason: String(targetFailure) })
    const hit = async () => {
      healthy()
      await context.command(`/staticfixture projectile hit ${targetName}`,
        /Fixture synthetic projectile hit dispatched/, TIMEOUT)
      healthy()
    }
    const start = async () => {
      const before = await value(context, 'arrows_fired')
      await context.command('/staticfixture projectile start', /Fixture synthetic projectile launched/, TIMEOUT)
      context.expect(await value(context, 'arrows_fired') === before + 1,
        'Synthetic projectile launch was not recorded exactly once')
    }
    const tracking = async enabled => {
      const content = originalConfig.replace(/(\[general\][\s\S]*?^enabled\s*=\s*)(true|false)/m,
        `$1${enabled}`)
      context.expect(content !== originalConfig || enabled, 'Global tracking setting was not found')
      const applied = context.waitForMessage(/Applied configuration and language file changes/i, 15000)
      await writeFile(configFile, content)
      await applied
      await context.command('/static status', new RegExp(`Tracking:\\s*${enabled}\\b`, 'i'), TIMEOUT)
    }
    try {
      await event(target, 'spawn', 20000)
      await mode(context, context.bot, 'survival')
      await mode(context, target, 'survival', targetName)
      await context.command(`/tp ${targetName} ${context.bot.username}`, /Teleported/i, TIMEOUT)
      await until(context, () => target.entity.position.distanceTo(context.bot.entity.position) <= 1.5,
        'The secondary player did not arrive beside the shooter')
      await context.sleep(500)
      healthy()
      context.report.staticProjectileProof = {
        kind: 'synthetic event dispatch',
        physicalProjectileHits: false,
        crossRegionTraversal: false,
        target: { username: target.username, uuid: target.player.uuid, version: target.version }
      }
      const health = target.health
      await context.step('disabled global tracking does not consume a piercing arrow unique-hit credit', async () => {
        const dealt = await value(context, 'arrow_hits_dealt')
        const taken = await value(context, 'arrow_hits_taken', targetName)
        await start()
        await tracking(false)
        await hit()
        context.expect(await value(context, 'arrow_hits_dealt') === dealt,
          'Disabled global tracking counted an outgoing hit')
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken,
          'Disabled global tracking counted an incoming hit')
        await tracking(true)
        await hit()
        context.expect(await value(context, 'arrow_hits_dealt') === dealt + 1,
          'Re-enabled tracking lost the piercing arrow outgoing hit')
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken + 1,
          'Re-enabled tracking lost the piercing arrow incoming hit')
        const fired = await value(context, 'arrows_fired')
        context.expect(await value(context, 'arrow_accuracy') === (dealt + 1) / fired,
          'The restored eligible hit did not contribute to arrow accuracy')
      })
      await context.step('an ineligible shooter does not consume outgoing piercing-arrow hit credit', async () => {
        const dealt = await value(context, 'arrow_hits_dealt')
        const taken = await value(context, 'arrow_hits_taken', targetName)
        await start()
        await mode(context, context.bot, 'creative')
        await hit()
        context.expect(await value(context, 'arrow_hits_dealt') === dealt,
          'Creative shooter hit was counted while creative tracking was disabled')
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken + 1,
          'Eligible victim did not independently receive incoming hit credit')
        await mode(context, context.bot, 'survival')
        await hit()
        context.expect(await value(context, 'arrow_hits_dealt') === dealt + 1,
          'The shooter lost outgoing hit credit after becoming eligible')
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken + 1,
          'The same victim was counted twice for one piercing arrow')
      })
      await context.step('an ineligible victim does not consume incoming piercing-arrow hit credit', async () => {
        const dealt = await value(context, 'arrow_hits_dealt')
        const taken = await value(context, 'arrow_hits_taken', targetName)
        await start()
        await mode(context, target, 'creative', targetName)
        await hit()
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken,
          'Creative victim hit was counted while creative tracking was disabled')
        context.expect(await value(context, 'arrow_hits_dealt') === dealt + 1,
          'Eligible shooter did not independently receive outgoing hit credit')
        await mode(context, target, 'survival', targetName)
        await hit()
        context.expect(await value(context, 'arrow_hits_taken', targetName) === taken + 1,
          'The victim lost incoming hit credit after becoming eligible')
        context.expect(await value(context, 'arrow_hits_dealt') === dealt + 1,
          'One piercing arrow counted outgoing hit credit twice')
      })
      healthy()
      context.expect(target.health === health, 'Synthetic event dispatch unexpectedly changed real player health')
    } finally {
      try {
        if (await readFile(configFile, 'utf8') !== originalConfig) {
          const applied = context.waitForMessage(/Applied configuration and language file changes/i, 15000)
          await writeFile(configFile, originalConfig)
          await applied
        }
        await mode(context, context.bot, 'survival')
        if (target.entity && !target._client.ended) await mode(context, target, 'survival', targetName)
        await context.command('/staticfixture projectile clear', /Fixture synthetic projectile cleared/, TIMEOUT)
      } finally {
        expectedEnd = true
        if (!target._client.ended) {
          const disconnected = event(target, 'end', 5000)
          target.quit('Static projectile checks complete')
          await disconnected
        }
        target.removeListener('error', failed)
        target.removeListener('kicked', kicked)
        target.removeListener('end', ended)
      }
    }
  }
}
