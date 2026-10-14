import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { request as httpRequest } from 'node:http'
import path from 'node:path'

const TIMEOUT = 15000
const UNKNOWN = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(150)
  }
  context.expect(false, message)
}

async function ranking(context, actor) {
  const messages = []
  const listener = message => messages.push(String(message))
  actor.bot.on('messagestr', listener)
  try {
    actor.bot.chat('/static top blocks_broken')
    await until(context, () => messages.some(message => /Recorded totals.*Updated/.test(message)),
      'Ranking response did not complete')
    return messages
  } finally {
    actor.bot.removeListener('messagestr', listener)
  }
}

async function ownProfile(context, actor) {
  const opened = actor.waitForEvent('windowOpen', () => true, TIMEOUT)
  actor.bot.chat('/static stats')
  const [window] = await opened
  await until(context, () => window.slots.slice(0, window.inventoryStart).some(Boolean), 'Own profile did not render')
  actor.bot.closeWindow(window)
}

async function visibility(actor, mode, target) {
  const suffix = target ? ` ${target}` : ''
  await actor.command(`/static privacy ${mode}${suffix}`, /Visibility for .+ is now (Private|Server|Public)/, TIMEOUT)
}

function assertPublicProjection(context, profile, uuid) {
  context.expect(profile.uuid === uuid, 'Public profile identity differs from the requested player')
  context.expect(Object.keys(profile).sort().join(',') === 'bests,details,name,statistics,uuid',
    'Public profile exposes fields outside its allowlist', { fields: Object.keys(profile) })
  const permitted = {
    statistics: ['category', 'formatted', 'id', 'name', 'value'],
    details: ['id', 'name', 'value'],
    bests: ['formatted', 'period', 'stat', 'value']
  }
  for (const [section, keys] of Object.entries(permitted)) {
    context.expect(Array.isArray(profile[section]), 'Public profile section is not an array', { section })
    for (const entry of profile[section]) {
      context.expect(Object.keys(entry).every(key => keys.includes(key)),
        'Public projection exposes session or observation metadata', { section, fields: Object.keys(entry) })
    }
  }
}

export default {
  name: 'static-public-profiles',
  description: 'Verify explicit public sharing, private in-game views, anonymous HTTP boundaries, durable visibility, and restart.',
  async run(context) {
    const folder = process.env.STATIC_QA_PLUGIN_DIR
    const port = Number(process.env.STATIC_QA_DASHBOARD_PORT)
    context.expect(folder && Number.isInteger(port) && port > 1023, 'Public profile QA environment is missing')
    const origin = `http://127.0.0.1:${port}`
    const token = (await readFile(path.join(folder, 'dashboard/access-token'), 'utf8')).trim()
    const baselineFile = path.join(path.dirname(folder), 'StaticFixture/public-profiles-baseline.json')
    const persistedFile = path.join(folder, 'storage/profile-visibility.json')
    const primary = context.bot.player.uuid
    context.report.staticMode = process.env.STATIC_QA_MODE ?? 'seed'

    async function request(route, operator = false, options = {}) {
      return fetch(origin + route, { signal: AbortSignal.timeout(5000), ...options,
        headers: { ...(operator ? { Authorization: `Bearer ${token}` } : {}), ...options.headers } })
    }

    async function json(route, operator = false) {
      let response = await request(route, operator)
      const deadline = Date.now() + TIMEOUT
      while (response.status === 202 && Date.now() < deadline) {
        await response.arrayBuffer()
        await context.sleep(250)
        response = await request(route, operator)
      }
      context.expect(response.status === 200, 'Profile HTTP request failed', { route, status: response.status })
      return response.json()
    }

    async function unavailable(uuid) {
      const response = await request(`/api/public/profile?uuid=${uuid}`)
      context.expect(response.status === 404, 'Profile was exposed without public visibility', { uuid, status: response.status })
      context.expect(await response.text() === '{"error":"not_found"}', 'Unavailable profiles have distinguishable responses')
    }

    async function configure(publicEnabled) {
      const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
      await writeFile(path.join(folder, 'config.toml'), `[tracking]\nautosaveSeconds = 10\n[hotReload]\npollIntervalMillis = 250\ncooldownMillis = 250\n[dashboard]\nenabled = true\nport = ${port}\npublicOrigin = "${origin}"\npublicProfilesEnabled = ${publicEnabled}\n`)
      await applied
    }

    async function flush() {
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => {
        const result = await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)
        context.expect(!result.includes('failed'), 'Statistics flush failed')
        return result.includes('complete')
      }, 'Statistics flush did not complete')
    }

    if (context.report.staticMode === 'restart') {
      const baseline = JSON.parse(await readFile(baselineFile, 'utf8'))
      context.expect(primary === baseline.primary.uuid, 'Restart used a different primary player')
      await context.step('public and private choices remain durable after restart', async () => {
        const stored = JSON.parse(await readFile(persistedFile, 'utf8'))
        context.expect(stored.players[primary] === 'public' && stored.players[baseline.viewer.uuid] === 'private',
          'Saved visibility choices changed across restart', { players: stored.players })
        await context.command('/static privacy', /Visibility for .+:.*Public/, TIMEOUT)
        await context.command(`/static privacy current ${baseline.viewer.name}`, /Visibility for .+:.*Private/, TIMEOUT)
      })
      await context.step('restart public projection remains bounded and the private profile stays unavailable', async () => {
        const profile = await json(`/api/public/profile?uuid=${primary}`)
        assertPublicProjection(context, profile, primary)
        context.expect(profile.statistics.find(entry => entry.id === 'blocks_broken')?.value >= 40, 'Saved aggregate counter was lost')
        context.expect(profile.details.find(entry => entry.id === 'mined_diamond_ore')?.value === '9007199254740993',
          'Saved detailed integer lost precision')
        await unavailable(baseline.viewer.uuid)
        const operator = await json(`/api/profile?uuid=${baseline.viewer.uuid}`, true)
        context.expect(operator.uuid === baseline.viewer.uuid && Array.isArray(operator.sessions), 'Operator profile access was restricted by public visibility')
      })
      await context.step('shared rankings remain filtered after restart', async () => {
        const messages = await ranking(context, context)
        context.expect(messages.some(message => message.includes(baseline.primary.name)), 'Visible player is missing from the shared board')
        context.expect(!messages.some(message => message.includes(baseline.viewer.name)), 'Private player returned to the shared board after restart')
        context.report.restartSharedRanking = messages
      })
      return
    }

    let viewer
    await context.step('public endpoints are disabled by default while operator access remains available', async () => {
      await configure(false)
      await context.command('/static privacy', /Visibility for .+:.*Server/, TIMEOUT)
      await unavailable(primary)
      context.expect((await request(`/player/${primary}`)).status === 404, 'Disabled public page is reachable')
      context.expect((await request('/public.js')).status === 404, 'Disabled public script is reachable')
      context.expect((await request('/api/profile?uuid=' + primary)).status === 401, 'Operator profile no longer requires authentication')
      context.expect((await json(`/api/profile?uuid=${primary}`, true)).uuid === primary, 'Operator cannot inspect a server-only profile')
    })
    await context.step('ordinary players can inspect their visibility but cannot edit another player', async () => {
      viewer = await context.connectActor('StaticViewer')
      await viewer.command('/static privacy', /Visibility for StaticViewer:.*Server/, TIMEOUT)
      await viewer.command(`/static privacy public ${context.bot.username}`, /You do not have permission to do that/, TIMEOUT)
      await context.command('/static privacy', /Visibility for .+:.*Server/, TIMEOUT)
      context.report.viewerIdentity = { name: viewer.bot.username, uuid: viewer.bot.player.uuid, protocol: viewer.bot.version }
    })
    await context.step('enabling public pages does not publish existing server-only players', async () => {
      await configure(true)
      await unavailable(primary)
      await unavailable(viewer.bot.player.uuid)
      await unavailable(UNKNOWN)
      context.expect((await request(`/player/${primary}`)).status === 200, 'Public page shell is missing')
      context.expect((await request('/api/public/players')).status === 404, 'Anonymous player directory was exposed')
      context.expect((await request('/api/public/rankings')).status === 404, 'Anonymous aggregate rankings were exposed')
    })
    await context.step('explicit public consent exposes only aggregates and exact detailed counters', async () => {
      await context.command('/staticfixture add blocks_broken 40', /Fixture added/, TIMEOUT)
      await context.command('/staticfixture detail-add mined_diamond_ore 9007199254740993', /Fixture detail added/, TIMEOUT)
      await visibility(context, 'public')
      const profile = await json(`/api/public/profile?uuid=${primary}`)
      assertPublicProjection(context, profile, primary)
      context.expect(profile.statistics.find(entry => entry.id === 'blocks_broken')?.value >= 40, 'Public aggregate totals are incorrect')
      context.expect(profile.details.find(entry => entry.id === 'mined_diamond_ore')?.value === '9007199254740993',
        'Public detailed counter lost integer precision')
      context.report.publicFields = Object.keys(profile)
      const stored = JSON.parse(await readFile(persistedFile, 'utf8'))
      context.expect(stored.players[primary] === 'public', 'Public command acknowledged before durable storage')
    })
    await context.step('share links permit document navigation without relaxing API origin checks', async () => {
      const page = await new Promise((resolve, reject) => {
        const navigation = httpRequest(origin + `/player/${primary}`, { headers: {
          'Sec-Fetch-Site': 'cross-site', 'Sec-Fetch-Mode': 'navigate', 'Sec-Fetch-Dest': 'document'
        } }, response => {
          response.once('error', reject)
          response.once('end', () => resolve({ status: response.statusCode, headers: response.headers }))
          response.resume()
        })
        navigation.setTimeout(5000, () => navigation.destroy(new Error('Share navigation timed out')))
        navigation.once('error', reject)
        navigation.end()
      })
      context.expect(page.status === 200, 'Cross-site share link navigation was rejected')
      context.expect(page.headers['x-robots-tag']?.includes('noindex') && page.headers['cache-control'] === 'no-store',
        'Public page indexing or cache headers are missing')
      const cross = await request(`/api/public/profile?uuid=${primary}`, false, { headers: { Origin: 'https://other.example' } })
      context.expect(cross.status === 403 && !cross.headers.has('access-control-allow-origin'), 'Public API allowed a foreign origin')
      context.expect((await request(`/api/public/profile?uuid=${primary}&period=day`)).status === 400, 'Public API exposes period selection')
      const operator = await request(`/api/profile?uuid=${primary}`)
      context.expect(operator.status === 401, 'Public consent bypassed operator endpoint authentication')
    })
    await context.step('private mode revokes public access and ordinary in-game access but preserves owner and operator views', async () => {
      await visibility(context, 'private')
      await unavailable(primary)
      await viewer.command(`/static stats ${context.bot.username}`, /You do not have permission to do that/, TIMEOUT)
      await ownProfile(context, context)
      const operator = await json(`/api/profile?uuid=${primary}`, true)
      context.expect(operator.uuid === primary && operator.sessions.length > 0, 'Private visibility hid the operator profile')
      const stored = JSON.parse(await readFile(persistedFile, 'utf8'))
      context.expect(stored.players[primary] === 'private', 'Private command acknowledged before durable storage')
    })
    await context.step('shared rankings immediately hide private players and restore server-visible players', async () => {
      const hidden = await ranking(context, viewer)
      context.expect(!hidden.some(message => message.includes(context.bot.username)), 'Private player remains on the shared board')
      await visibility(context, 'server')
      await unavailable(primary)
      const visible = await ranking(context, viewer)
      context.expect(visible.some(message => message.includes(context.bot.username)), 'Restored server-only player is missing from the shared board')
      context.report.sharedRanking = { hidden, visible }
    })
    await context.step('ordinary players can set their own privacy and administrators can restore it', async () => {
      await visibility(viewer, 'private')
      await ownProfile(context, viewer)
      await context.command(`/static privacy current ${viewer.bot.username}`, /Visibility for StaticViewer:.*Private/, TIMEOUT)
      await visibility(context, 'server', viewer.bot.username)
      await viewer.command('/static privacy', /Visibility for StaticViewer:.*Server/, TIMEOUT)
      await visibility(viewer, 'private')
      await visibility(context, 'public')
      await unavailable(viewer.bot.player.uuid)
    })
    await context.step('public feature reload revokes and restores pages without exposing operator APIs', async () => {
      await configure(false)
      await unavailable(primary)
      context.expect((await request(`/player/${primary}`)).status === 404, 'Public page stayed available after disable')
      context.expect((await request('/api/meta', true)).status === 200, 'Public disable stopped operator access')
      await configure(true)
      assertPublicProjection(context, await json(`/api/public/profile?uuid=${primary}`), primary)
      context.expect((await request('/api/players')).status === 401, 'Anonymous operator directory became available after reload')
    })
    await context.step('final public and private choices are durable for restart and localized labels are available', async () => {
      const catalog = await json('/api/public/messages')
      context.expect(catalog.locale === 'en_US' && catalog.messages['dashboard.publicProfile'], 'Public page language labels are missing')
      await flush()
      const stored = JSON.parse(await readFile(persistedFile, 'utf8'))
      context.expect(stored.players[primary] === 'public' && stored.players[viewer.bot.player.uuid] === 'private',
        'Final visibility storage differs from acknowledged choices', { players: stored.players })
      await mkdir(path.dirname(baselineFile), { recursive: true })
      await writeFile(baselineFile, JSON.stringify({ primary: { uuid: primary, name: context.bot.username },
        viewer: { uuid: viewer.bot.player.uuid, name: viewer.bot.username } }, null, 2) + '\n')
      context.report.visibility = stored.players
    })
  }
}
