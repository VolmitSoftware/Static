import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const TIMEOUT = 20000
const PEER = '00000000-0000-0000-0000-000000000093'

async function until(context, predicate, message) {
  const deadline = Date.now() + TIMEOUT
  while (Date.now() < deadline) {
    if (await predicate()) return
    await context.sleep(200)
  }
  context.expect(false, message)
}

export default {
  name: 'static-web-reports',
  description: 'Verify authenticated seasons, milestones, exact detailed rankings and retained-history comparisons on a running server.',
  async run(context) {
    const folder = process.env.STATIC_QA_PLUGIN_DIR
    const port = Number(process.env.STATIC_QA_DASHBOARD_PORT)
    context.expect(folder && Number.isInteger(port) && port > 1023, 'Dashboard QA environment is missing')
    const origin = `http://127.0.0.1:${port}`
    const token = (await readFile(path.join(folder, 'dashboard', 'access-token'), 'utf8')).trim()
    const uuid = context.bot.player.uuid
    const routes = ['/api/seasons', '/api/season?id=report-event', '/api/detail-rankings?counter=mined_diamond_ore',
      `/api/milestones?uuid=${uuid}`, `/api/comparison?uuid=${uuid}&stat=blocks_broken&period=day&count=3`]
    async function request(route, authenticated = true) {
      return fetch(origin + route, { signal: AbortSignal.timeout(4000), headers: authenticated ? { Authorization: `Bearer ${token}` } : {} })
    }
    async function json(route) {
      let response
      await until(context, async () => {
        response = await request(route)
        if (response.status !== 202) return true
        await response.arrayBuffer()
        return false
      }, 'Report remained warming')
      context.expect(response.status === 200, 'Report request failed', { route, status: response.status })
      return response.json()
    }
    await context.step('new reports require operator authentication and remain absent from public routes', async () => {
      for (const route of routes) {
        const response = await request(route, false)
        context.expect(response.status === 401, 'Report did not require authentication', { route, status: response.status })
        context.expect(!(await response.text()).includes(context.bot.username), 'Unauthorized report contained player data')
      }
      const response = await request('/api/public/comparison', false)
      context.expect(response.status === 404, 'Anonymous comparison route exists')
    })
    const start = new Date(Date.now() + 2000).toISOString()
    const end = new Date(Date.now() + 18000).toISOString()
    await context.step('live configuration creates an event and milestone thresholds', async () => {
      const applied = context.waitForMessage(/Applied configuration and language file changes/, TIMEOUT)
      const original = await readFile(path.join(folder, 'config.toml'), 'utf8')
      await writeFile(path.join(folder, 'config.toml'), original + `\n[milestones]\nenabled = true\nnotifyPlayers = false\n[milestones.thresholds]\nblocks_broken = [10, 50]\n[[seasons.events]]\nid = "report-event"\nname = "Mining report event"\nstartsAt = "${start}"\nendsAt = "${end}"\nstatistic = "blocks_broken"\nminimumSample = 0.0\ntrackCreative = true\ntrackSpectator = false\nafkSeconds = 300\nexcludedWorlds = []\n`)
      await applied
      await until(context, async () => (await json('/api/season?id=report-event')).season.status === 'active', 'Season did not activate')
    })
    await context.step('recorded player activity populates exact detailed standings and shared season ranks', async () => {
      await context.command('/staticfixture add blocks_broken 40', /Fixture added/, TIMEOUT)
      await context.command(`/staticfixture add-uuid ${PEER} blocks_broken 40`, /Fixture UUID added/, TIMEOUT)
      await context.command('/staticfixture detail-add mined_diamond_ore 9007199254740993', /Fixture detail added/, TIMEOUT)
      await until(context, async () => {
        const report = await json('/api/detail-rankings?counter=mined_diamond_ore&size=1')
        return report.entries[0]?.uuid === uuid && report.entries[0]?.value === '9007199254740993'
      }, 'Detailed ranking lost precision or player identity')
      await until(context, async () => {
        const report = await json('/api/season?id=report-event')
        return report.ranking.entries.length === 2 && report.ranking.entries.every(entry => entry.rank === 1 && entry.value === 40)
      }, 'Season report did not preserve tied scores')
    })
    await context.step('milestone report distinguishes completed and unfinished thresholds', async () => {
      await until(context, async () => {
        const report = await json(`/api/milestones?uuid=${uuid}`)
        return report.entries.some(entry => entry.stat === 'blocks_broken' && entry.threshold === 10 && entry.completed)
          && report.entries.some(entry => entry.stat === 'blocks_broken' && entry.threshold === 50 && !entry.completed)
      }, 'Milestone report does not match earned progress')
    })
    await context.step('comparison aligns calendar windows and leaves unobserved history absent', async () => {
      const report = await json(`/api/comparison?uuid=${uuid}&other=${PEER}&stat=blocks_broken&period=day&count=3`)
      context.expect(report.windows.length === 3 && report.players.length === 2, 'Comparison bounds differ')
      for (const player of report.players) {
        context.expect(player.points.length === 3 && player.points[0] === null && player.points[1] === null,
          'Missing history was manufactured', player)
        context.expect(player.points[2]?.value === 40, 'Current recorded score is missing', player)
      }
      context.report.comparison = report
    })
    await context.step('private visibility does not remove trusted operator reports', async () => {
      await context.command('/static privacy private', /now.*Private|now.*private/, TIMEOUT)
      context.expect((await json(`/api/milestones?uuid=${uuid}`)).uuid === uuid, 'Operator milestone access changed')
      context.expect((await json(`/api/comparison?uuid=${uuid}&stat=blocks_broken&period=day&count=1`)).players[0].uuid === uuid,
        'Operator comparison access changed')
      context.expect((await request(`/api/public/profile?uuid=${uuid}`, false)).status === 404, 'Private profile became public')
    })
    await context.step('invalid bounds and missing resources fail without successful empty reports', async () => {
      for (const route of ['/api/seasons?size=51', '/api/detail-rankings?counter=unknown', `/api/milestones?uuid=${uuid}&page=-1`,
        `/api/comparison?uuid=${uuid}&stat=blocks_broken&count=13`, `/api/comparison?uuid=${uuid}&stat=blocks_broken&period=lifetime`]) {
        context.expect((await request(route)).status === 400, 'Invalid report query accepted', { route })
      }
      for (const route of ['/api/season?id=missing', '/api/milestones?uuid=00000000-0000-0000-0000-000000000099']) {
        context.expect((await request(route)).status === 404, 'Missing resource not distinguished', { route })
      }
    })
    await context.step('archived event reports retain tied winners and survive a completed flush', async () => {
      await until(context, async () => (await json('/api/season?id=report-event')).season.status === 'archived', 'Season did not archive')
      const report = await json('/api/season?id=report-event')
      context.expect(report.winnersTotal === 2 && report.winners.length === 2, 'Tied winners missing', report)
      await context.command('/staticfixture flush', /Fixture flush requested/, TIMEOUT)
      await until(context, async () => (await context.command('/staticfixture saved', /Fixture flush (complete|pending|failed)/, TIMEOUT)).includes('complete'), 'Flush did not complete')
      await writeFile(path.join(folder, '..', 'StaticFixture', 'reports-baseline.json'), JSON.stringify({ uuid, peer: PEER, season: report, counter: '9007199254740993' }))
      context.report.season = report
    })
  }
}
