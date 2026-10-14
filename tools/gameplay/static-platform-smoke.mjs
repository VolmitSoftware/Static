import { readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 8000

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function value(context, key) {
  const expression = new RegExp(`Fixture value ${key} ([0-9.e+-]+)`)
  const response = await context.command(`/staticfixture value ${key}`, expression, TIMEOUT)
  const result = Number(response.match(expression)[1])
  context.expect(Number.isFinite(result), `Invalid public API value for ${key}`, { response })
  return result
}

async function flush(context) {
  await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
  await until(context, async () => {
    const response = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
    context.expect(!response.includes('failed'), 'Public API persistence failed')
    return response.includes('complete')
  }, 'Public API persistence did not finish')
}

async function stored(context, directory) {
  const document = JSON.parse(await readFile(path.join(directory, 'storage', 'players.json'), 'utf8'))
  context.expect(document.version === 1, 'Unexpected statistics storage version')
  const player = document.players.find(entry => entry.uuid === context.report.bot.uuid)
  context.expect(player?.name === context.bot.username, 'The current player profile was not persisted')
  return player
}

async function diagnostic(context, directory) {
  const debug = path.join(directory, 'debug')
  const previous = new Set(await readdir(debug).catch(error => {
    if (error.code === 'ENOENT') return []
    throw error
  }))
  await context.command('/static debug dump upload=false', /saved|written|local report/i, TIMEOUT)
  const files = (await readdir(debug)).filter(file => !previous.has(file) && file.endsWith('.txt'))
  context.expect(files.length === 1, 'Expected one new local diagnostic report', { files })
  const report = await readFile(path.join(debug, files[0]), 'utf8')
  context.expect(report.includes('Java bytecode target: 25'), 'Diagnostic bytecode target does not report Java 25')
  context.expect(/Java version:\s*25\b/i.test(report), 'Diagnostic runtime is not Java 25')
  context.expect(/Last save failure:\s*(?:none|null)/i.test(report), 'Diagnostic reports a failed statistics save')
  context.report.staticDiagnostic = { file: files[0], bytecodeTarget: 25 }
}

export default {
  name: 'static-platform-smoke',
  description: 'Verify Java 25 status, public statistics, durable storage, diagnostics, and restart recovery.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const baselineFile = process.env.STATIC_QA_BASELINE
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory && baselineFile, 'Set STATIC_QA_PLUGIN_DIR and STATIC_QA_BASELINE')
    context.expect(mode === 'seed' || mode === 'restart', 'STATIC_QA_MODE must be seed or restart')
    context.report.staticMode = mode
    context.report.staticProtocol = context.bot.version

    await context.step('spawned player is alive on the reported protocol', async () => {
      context.expect(context.bot.health > 0, 'The spawned player is not alive')
      context.expect(Number.isFinite(context.bot.entity.position.x), 'The player position is invalid')
    })

    await context.step('status reports Java 25 and active tracking', async () => {
      await context.command('/static status', /Java 25\s*\/\s*Bukkit 26\.1\+/, TIMEOUT)
      await context.command('/static status', /Tracking:\s*true.*Profiles:\s*[1-9]\d*/, TIMEOUT)
    })

    await context.step('fresh installation has only the English language file', async () => {
      const languages = (await readdir(path.join(directory, 'languages'))).filter(file => file.endsWith('.toml'))
      context.expect(languages.length === 1 && languages[0] === 'en_US.toml',
        'Unexpected generated language catalogue', { languages })
    })

    await context.step('statistics inventory opens with populated contents and closes', async () => {
      const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('/stats')
      const [window] = await opened
      context.expect(window.inventoryStart === 54, 'Statistics inventory has an unexpected size')
      await until(context, () => window.slots.slice(0, 54).filter(Boolean).length >= 18,
        'Statistics inventory did not receive its statistic items')
      context.report.staticInventory = {
        title: window.title,
        size: window.inventoryStart,
        populatedSlots: window.slots.slice(0, 54).filter(Boolean).length
      }
      context.bot.closeWindow(window)
      await until(context, () => context.bot.currentWindow === null, 'Statistics inventory did not close')
    })

    await context.step('public statistics service is registered and tracks the join', async () => {
      await context.command('/staticfixture', /Fixture ready/, TIMEOUT)
      context.expect(await value(context, 'joins') >= 1, 'Public API did not record the player join')
      context.expect(await value(context, 'online_time') > 0, 'Public API did not include live session time')
    })

    if (mode === 'seed') {
      await context.step('accepted player chat increments the public counter exactly once', async () => {
        const before = await value(context, 'chat_messages')
        await context.command('Static platform persistence sample', /Static platform persistence sample/, TIMEOUT)
        await until(context, async () => await value(context, 'chat_messages') === before + 1,
          'Accepted chat did not increment the public counter exactly once')
      })
    } else {
      await context.step('restart restores counters and records a new session', async () => {
        const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
        context.expect(baseline.uuid === context.report.bot.uuid, 'Restart bot identity differs from the seeded profile')
        context.expect(await value(context, 'chat_messages') === baseline.values.chat_messages,
          'Chat total changed across restart')
        context.expect(await value(context, 'joins') === baseline.values.joins + 1,
          'Restart did not restore and increment the join total')
        context.expect(await value(context, 'quits') >= (baseline.values.quits ?? 0) + 1,
          'Shutdown did not retain the previous quit')
        context.expect(await value(context, 'online_time') >= baseline.values.online_time,
          'Accumulated session time regressed across restart')
      })
    }

    await context.step('public flush durably stores the observed counters', async () => {
      const messages = await value(context, 'chat_messages')
      const joins = await value(context, 'joins')
      await flush(context)
      const player = await stored(context, directory)
      context.expect(player.values.chat_messages === messages, 'Durable chat total differs from the public API')
      context.expect(player.values.joins === joins, 'Durable join total differs from the public API')
      context.expect(player.values.online_time > 0, 'Durable session time was not saved')
      if (mode === 'seed') await writeFile(baselineFile, JSON.stringify(player, null, 2) + '\n')
      context.report.staticStored = player
    })

    await context.step('local debug dump reports Java 25 and healthy persistence', async () => {
      await diagnostic(context, directory)
    })
  }
}
