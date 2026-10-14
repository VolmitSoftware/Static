import { readFile, readdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 15000

async function until(context, predicate, message, timeout = TIMEOUT) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(100)
  }
  context.expect(false, message)
}

async function status(context, interval) {
  await context.command('/static status', new RegExp(`Autosave: ${interval}s`), TIMEOUT)
}

async function configure(context, directory, original, interval) {
  const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
  await writeFile(path.join(directory, 'config.toml'), original.replace(/^autosaveSeconds\s*=\s*\d+/m, `autosaveSeconds = ${interval}`))
  await applied
  await status(context, interval)
}

async function player(directory, uuid) {
  try {
    const data = JSON.parse((await readFile(path.join(directory, 'storage', 'players.json'), 'utf8')).replace(/^\uFEFF/, ''))
    return data.players.find(entry => entry.uuid === uuid)
  } catch (error) {
    if (error.code === 'ENOENT') return undefined
    throw error
  }
}

async function synchronize(context) {
  let timeout
  try {
    await Promise.race([context.bot._syncWindow(context.bot.inventory), new Promise((resolve, reject) => {
      timeout = setTimeout(() => reject(new Error('Personal inventory did not acknowledge the menu closure')), TIMEOUT)
    })])
  } finally {
    clearTimeout(timeout)
  }
}

export default {
  name: 'static-baseline',
  description: 'Verify the Java25/Minecraft26.1–26.3 contract, profile inventory, hot reload, diagnostics, and restart persistence.',
  async run(context) {
    const directory = process.env.STATIC_QA_PLUGIN_DIR
    const baselineFile = process.env.STATIC_QA_BASELINE
    const mode = process.env.STATIC_QA_MODE ?? 'seed'
    context.expect(directory && baselineFile && ['seed', 'restart'].includes(mode), 'Baseline environment is incomplete')
    context.report.staticMode = mode
    await context.step('spawned player can query the supported Java25 and Minecraft range', async () => {
      context.expect(context.bot.health > 0 && Number.isFinite(context.bot.entity.position.x), 'Spawned player state is invalid')
      await context.command('/static status', /Java 25\+?\s*\/\s*Bukkit 26\.1-26\.3/, TIMEOUT)
      await context.command('/static status', /Tracking:\s*true/, TIMEOUT)
    })
    await context.step('startup generates only English', async () => {
      const languages = (await readdir(path.join(directory, 'languages'))).filter(file => file.endsWith('.toml'))
      context.expect(languages.length === 1 && languages[0] === 'en_US.toml', 'Unexpected generated language files', languages)
    })
    await context.step('profile inventory populates and refreshes on the owning scheduler', async () => {
      const opened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      context.bot.chat('/stats')
      const [window] = await opened
      context.expect(window.inventoryStart === 54, 'Profile inventory size differs')
      await until(context, () => context.bot.currentWindow?.slots[49]
        && context.bot.currentWindow.slots.slice(0, 45).filter(Boolean).length >= 30, 'Profile items did not arrive')
      const refreshed = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      await context.bot.clickWindow(49, 0, 0)
      await refreshed
      await until(context, () => context.bot.currentWindow?.slots[49], 'Refreshed inventory controls did not arrive')
      context.report.inventory = { title: context.bot.currentWindow.title, size: 54 }
      context.bot.closeWindow(context.bot.currentWindow)
      await until(context, () => context.bot.currentWindow === null, 'Profile inventory did not close')
      await synchronize(context)
    })
    await context.step('configuration hot reload applies and preserves the selected interval', async () => {
      const original = await readFile(path.join(directory, 'config.toml'), 'utf8')
      context.expect(/^autosaveSeconds\s*=\s*\d+/m.test(original), 'Autosave setting is missing')
      if (mode === 'restart') {
        await status(context, 11)
        await configure(context, directory, original, 12)
      }
      await configure(context, directory, original, 11)
    })
    await context.step('local diagnostic reports Java25 and the supported server range', async () => {
      const debug = path.join(directory, 'debug')
      const before = new Set(await readdir(debug).catch(error => { if (error.code === 'ENOENT') return []; throw error }))
      await context.command('/static debug dump upload=false', /saved|written|local report/i, TIMEOUT)
      const files = (await readdir(debug)).filter(file => file.endsWith('.txt') && !before.has(file))
      context.expect(files.length === 1, 'Expected one new local diagnostic')
      const dump = await readFile(path.join(debug, files[0]), 'utf8')
      context.expect(dump.includes('Java bytecode target: 25') && /Java version:\s*25\b/.test(dump)
        && dump.includes('Minecraft support: 26.1-26.3'), 'Diagnostic compatibility metadata differs')
      context.expect(/Last save failure:\s*(?:none|null)/i.test(dump), 'Diagnostic contains a persistence failure')
      context.report.diagnostic = files[0]
    })
    await context.step('recorded chat and joins remain durable across restart', async () => {
      const uuid = context.report.bot.uuid
      const baseline = mode === 'restart' ? JSON.parse(await readFile(baselineFile, 'utf8')) : undefined
      if (!baseline) await context.command('Static baseline durability sample', /Static baseline durability sample/, TIMEOUT)
      let stored
      await until(context, async () => {
        stored = await player(directory, uuid)
        return stored?.values.chat_messages === (baseline?.values.chat_messages ?? 1)
          && stored.values.joins === (baseline ? baseline.values.joins + 1 : 1)
      }, 'Durable counters did not match the observed chat and join', 25000)
      if (baseline) context.expect(stored.uuid === baseline.uuid && stored.values.online_time >= baseline.values.online_time,
        'Restart lost the player identity or accumulated time')
      else await writeFile(baselineFile, JSON.stringify(stored, null, 2))
      context.report.stored = stored
    })
  }
}
