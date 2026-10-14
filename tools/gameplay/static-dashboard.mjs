import { readFile, writeFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import path from 'node:path'

const TIMEOUT = 15000

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(150)
  }
  context.expect(false, message)
}

export default {
  name: 'static-dashboard',
  description: 'Verify authenticated dashboard data against a real player, reload, token rotation, and in-game configuration controls.',
  async run(context) {
    const folder = process.env.STATIC_QA_PLUGIN_DIR
    const port = Number(process.env.STATIC_QA_DASHBOARD_PORT)
    context.expect(folder && Number.isInteger(port) && port > 1023, 'Dashboard QA environment is missing')
    const origin = `http://127.0.0.1:${port}`
    let token = (await readFile(path.join(folder, 'dashboard', 'access-token'), 'utf8')).trim()
    async function request(route, options = {}) {
      return fetch(origin + route, { signal: AbortSignal.timeout(4000), ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers } })
    }
    async function json(route) {
      const deadline = Date.now() + TIMEOUT
      let response = await request(route)
      while (response.status === 202 && Date.now() < deadline) {
        await response.arrayBuffer()
        await context.sleep(250)
        response = await request(route)
      }
      context.expect(response.status === 200, 'Dashboard data request failed', { route, status: response.status })
      return response.json()
    }
    async function configure(enabled) {
      const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
      await writeFile(path.join(folder, 'config.toml'), `[tracking]\nautosaveSeconds = 10\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[dashboard]\nenabled = ${enabled}\nport = ${port}\npublicOrigin = "${origin}"\n`)
      await applied
    }
    await context.step('unauthenticated and wrong-token API requests reveal no profile data', async () => {
      for (const headers of [{ Authorization: '' }, { Authorization: 'Bearer invalid' }]) {
        const response = await request('/api/players', { headers })
        context.expect(response.status === 401, 'Authentication was not required', { status: response.status })
        context.expect(!(await response.text()).includes(context.bot.username), 'Unauthorized response contained player data')
      }
    })
    await context.step('browser response protections and cross-origin rejection apply', async () => {
      const response = await request('/api/meta')
      context.expect(response.status === 200 && response.headers.get('cache-control')?.includes('no-store'), 'Private response cache policy missing')
      context.expect(response.headers.get('content-security-policy')?.includes("frame-ancestors 'none'"), 'Frame protection missing')
      context.expect(response.headers.get('x-content-type-options') === 'nosniff', 'MIME protection missing')
      const cross = await request('/api/meta', { headers: { Origin: 'https://other.example' } })
      context.expect(cross.status === 403 && !cross.headers.has('access-control-allow-origin'), 'Cross-origin request was allowed')
      const mutation = await request('/api/players', { method: 'POST' })
      context.expect(mutation.status === 405, 'Dashboard accepted a mutating method')
    })
    await context.step('real connection appears in bounded directory and participation data', async () => {
      await until(context, async () => {
        const directory = await json(`/api/players?q=${context.bot.username}&page=0&size=1`)
        return directory.entries.length === 1 && directory.entries[0].uuid === context.bot.player.uuid
      }, 'Connected player absent from dashboard')
      await until(context, async () => (await json('/api/overview?period=day&offset=0')).participation.uniquePlayers >= 1,
        'Observed connection absent from participation')
      const oversized = await request('/api/players?size=51')
      context.expect(oversized.status === 400, 'Oversized result page accepted')
    })
    await context.step('profile and rankings reflect API statistics with exact detailed integers', async () => {
      await context.command('/staticfixture add blocks_broken 40', /Fixture added/, TIMEOUT)
      await context.command('/staticfixture detail-add mined_diamond_ore 9007199254740993', /Fixture detail added/, TIMEOUT)
      const profile = await json(`/api/profile?uuid=${context.bot.player.uuid}&period=lifetime`)
      context.expect(profile.uuid === context.bot.player.uuid && profile.statistics.find(entry => entry.id === 'blocks_broken')?.value >= 40,
        'Profile does not reflect recorded totals')
      const detailed = profile.details.find(entry => entry.id === 'mined_diamond_ore')
      context.expect(detailed?.value === '9007199254740993', 'Detailed integer lost precision', { value: detailed?.value })
      await until(context, async () => {
        const rankings = await json('/api/rankings?stat=blocks_broken&period=lifetime&page=0&size=1')
        return rankings.entries.length === 1 && rankings.entries[0].uuid === context.bot.player.uuid
      }, 'Ranking did not show recorded player')
      context.expect(profile.sessions.length >= 1 && profile.sessions.length <= 50, 'Profile sessions are missing or unbounded')
    })
    await context.step('dashboard configuration is available in the in-game editor', async () => {
      const opened = context.waitForEvent('windowOpen', window => window.slots.length > 0, TIMEOUT)
      context.bot.chat('/static config')
      const [window] = await opened
      await until(context, () => window.slots.slice(0, window.inventoryStart).some(Boolean), 'Configuration contents did not arrive')
      const generalOpened = context.waitForEvent('windowOpen', () => true, TIMEOUT)
      await context.bot.clickWindow(20, 0, 0)
      const [general] = await generalOpened
      await until(context, () => general.slots.slice(0, general.inventoryStart).filter(item => item?.name === 'comparator').length === 2,
        'Dashboard settings did not appear in General')
      context.bot.closeWindow(general)
    })
    await context.step('hot reload can stop and re-enable the dashboard', async () => {
      await configure(false)
      let stopped = false
      try { await request('/api/meta') } catch { stopped = true }
      context.expect(stopped, 'Disabled dashboard still accepts connections')
      await configure(true)
      context.expect((await request('/api/meta')).status === 200, 'Dashboard did not resume with the persisted token')
    })
    await context.step('token rotation rejects the prior credential after re-enable', async () => {
      await configure(false)
      const previous = token
      token = randomBytes(32).toString('base64url')
      await writeFile(path.join(folder, 'dashboard', 'access-token'), token + '\n')
      await configure(true)
      context.expect((await request('/api/meta', { headers: { Authorization: `Bearer ${previous}` } })).status === 401,
        'Prior token still authorizes after rotation')
      context.expect((await request('/api/meta')).status === 200, 'Rotated token did not authorize')
    })
    await context.step('translated catalog and ordinary commands remain available', async () => {
      const messages = await json('/api/messages')
      context.expect(messages.locale === 'en_US' && messages.messages['dashboard.overview'], 'Dashboard English catalog missing')
      await context.command('/static status', /Static|Tracking|Profiles/i, TIMEOUT)
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => /complete/.test(await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)),
        'Final statistics save did not complete')
    })
  }
}
