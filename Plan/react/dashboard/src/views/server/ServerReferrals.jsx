import React, {useMemo, useState} from 'react';
import {useParams} from 'react-router';
import {Alert, Button, Card, Col, Form, Row} from 'react-bootstrap';
import {useReferrals} from '../../dataHooks/referralsHook.js';
import Graph from '../../components/graphs/Graph.jsx';
import LoadIn from '../../components/animation/LoadIn.tsx';
import {
    amount, GROUP_LABELS, percent, REFERRAL_GROUPS, REFERRAL_WINDOWS, retentionCell,
    retentionSeries, WINDOW_LABELS
} from '../../util/referralAnalytics.js';
import '../../style/referrals.css';

const number = value => value === null || value === undefined ? '—' : value.toLocaleString(undefined, {maximumFractionDigits: 1});
const date = value => value ? new Date(value).toLocaleString() : 'Not yet recorded';
const Panel = ({title, children, className = ''}) => <Card className={`shadow mb-4 ${className}`}>
    <Card.Header><h2 className="h6 mb-0">{title}</h2></Card.Header><Card.Body>{children}</Card.Body>
</Card>;

function Coverage({data}) {
    const {coverage} = data;
    const state = {
        disabled: ['secondary', 'Referral analytics is disabled. Collection must be enabled before these measures become available.'],
        unavailable: ['warning', 'Referral analytics is unavailable. Missing observations are not counted as zero activity.'],
        stale: ['warning', 'These observations are stale. Results stop at the last confirmed coverage; recent activity or referrals may be missing.'],
        collecting: ['info', 'Collecting observations. A return window enters the results only after it has fully elapsed with adequate coverage.']
    }[coverage.status];
    return <>
        {state && <Alert variant={state[0]} role="status">{state[1]}</Alert>}
        <p className="small mb-3 referrals-coverage">
            Collection started: {date(coverage.started_at)} · Activity through: {date(coverage.activity_through)}
            {' · '}Referral feed through: {date(coverage.referral_through)}
            {coverage.unknown_gaps > 0 && <> · {number(coverage.unknown_gaps)} coverage gaps</>}
        </p>
    </>;
}

function Comparison({data, windows}) {
    const options = useMemo(() => ({
        title: {text: ''}, chart: {type: 'line', animation: false}, credits: {enabled: false},
        xAxis: {categories: windows.map(window => WINDOW_LABELS[window]), title: {text: 'Elapsed time since first join'}},
        yAxis: {min: 0, max: 100, title: {text: 'Returned players (%)'}},
        legend: {enabled: true}, plotOptions: {series: {animation: false}, line: {marker: {enabled: true}}},
        tooltip: {shared: false, useHTML: false, valueDecimals: 1, valueSuffix: '%',
            pointFormatter: function () {
                const row = this.custom;
                return row ? `${this.series.name}: ${percent(row.rate)} (${row.retained}/${row.eligible}); 95% interval ${percent(row.lower)}–${percent(row.upper)}; ${row.incomplete} incomplete`
                    : `${number(this.low)}–${number(this.high)}%`;
            }},
        accessibility: {description: 'Return rates with 95% confidence intervals. Exact counts and incomplete windows are in the table below.'},
        series: retentionSeries(data.comparison, windows)
    }), [data.comparison, windows]);
    const hasSample = data.comparison.some(group => windows.some(window => retentionCell(group, window)));
    return <Panel title="Return activity by referral attribution">
        <p className="small">At least {data.coverage.return_minimum_minutes} active minutes in the selected window. Bars show 95% confidence intervals.</p>
        {hasSample ? <Graph id="referrals-comparison-chart" options={options}/>
            : <p className="referrals-empty" role="status">No complete return windows yet.</p>}
        <details className="mt-3"><summary>Sample counts and uncertainty</summary>
            <div className="table-responsive" tabIndex="0" role="region" aria-label="Retention comparison samples">
                <table className="table table-sm referrals-table"><thead><tr>
                    <th scope="col">Group</th><th scope="col">Window</th><th scope="col">Returned / eligible</th>
                    <th scope="col">Rate</th><th scope="col">95% interval</th><th scope="col">Incomplete</th>
                </tr></thead><tbody>{data.comparison.flatMap(group => windows.map(window => {
                    const row = group.retention.find(value => value.window === window);
                    const cell = retentionCell(group, window);
                    return <tr key={`${group.group}-${window}`}><th scope="row">{GROUP_LABELS[group.group]}</th>
                        <td>{WINDOW_LABELS[window]}</td><td>{row ? `${number(row.retained)} / ${number(row.eligible)}` : '—'}</td>
                        <td>{cell ? percent(cell.rate) : 'Collecting'}</td><td>{cell ? `${percent(cell.lower)}–${percent(cell.upper)}` : '—'}</td>
                        <td>{number(row?.incomplete)}</td></tr>;
                }))}</tbody></table>
            </div>
        </details>
        <p className="small mt-3 mb-0">Small samples have wider uncertainty. These comparisons describe association; they do not establish that referrals caused a return.</p>
    </Panel>;
}

function Cohorts({data, windows}) {
    const [selectedGroup, setSelectedGroup] = useState('recorded_referral');
    return <Panel title="First-join cohorts">
        <Form.Group controlId="referrals-cohort-group" className="mb-3">
            <Form.Label>Referral group</Form.Label>
            <Form.Select value={selectedGroup} onChange={event => setSelectedGroup(event.target.value)}>
                {REFERRAL_GROUPS.map(group => <option key={group} value={group}>{GROUP_LABELS[group]}</option>)}
            </Form.Select>
        </Form.Group>
        <p className="small">UTC first-join week. Each cell shows returned / eligible players; darker cells indicate a higher return rate.</p>
        {!data.cohorts.length ? <p className="referrals-empty">No first-join cohorts have been collected yet.</p>
            : <div className="table-responsive" tabIndex="0" role="region" aria-label="First-join cohort heatmap">
                <table className="table referrals-heatmap"><thead><tr>
                    <th scope="col">Week starting</th><th scope="col">Players</th>
                    {windows.map(window => <th scope="col" key={window}>{WINDOW_LABELS[window]}</th>)}
                </tr></thead><tbody>{data.cohorts.map(cohort => {
                    const group = cohort.groups.find(value => value.group === selectedGroup);
                    return <tr key={cohort.week}><th scope="row">{cohort.week}</th><td>{number(group?.players)}</td>
                        {windows.map(window => {
                            const cell = retentionCell(group, window);
                            const pending = group?.retention.find(value => value.window === window)?.incomplete;
                            return <td key={window} className={cell ? 'referrals-cell' : 'referrals-cell-empty'}
                                       style={cell ? {'--retention-opacity': 0.08 + cell.rate * 0.5} : undefined}>
                                <strong>{cell ? percent(cell.rate) : '—'}</strong>
                                <small>{cell ? `${number(cell.retained)} / ${number(cell.eligible)}` : 'No complete sample'}</small>
                                {pending > 0 && <small>{number(pending)} incomplete</small>}
                            </td>;
                        })}</tr>;
                })}</tbody></table>
            </div>}
    </Panel>;
}

function Activity({data}) {
    const [days, setDays] = useState(7);
    return <Panel title="Activity after first join">
        <Form.Group controlId="referrals-activity-days" className="mb-3">
            <Form.Label>Observation horizon</Form.Label>
            <Form.Select value={days} onChange={event => setDays(Number(event.target.value))}>
                <option value={7}>First 7 days</option><option value={30}>First 30 days</option>
            </Form.Select>
        </Form.Group>
        <div className="table-responsive" tabIndex="0" role="region" aria-label="Activity by referral attribution">
            <table className="table table-sm referrals-table"><thead><tr>
                <th scope="col">Group</th><th scope="col">Median active minutes</th><th scope="col">Median active days</th>
                <th scope="col">Eligible</th><th scope="col">Incomplete</th>
            </tr></thead><tbody>{data.comparison.map(group => {
                const row = group.activity.find(value => value.days === days);
                return <tr key={group.group}><th scope="row">{GROUP_LABELS[group.group]}</th>
                    <td>{number(row?.median_minutes)}</td><td>{number(row?.median_active_days)}</td>
                    <td>{number(row?.eligible)}</td><td>{number(row?.incomplete)}</td></tr>;
            })}</tbody></table>
        </div>
        <p className="small mb-0">Plan active time, excluding AFK, for both groups. An active day has at least {data.coverage.return_minimum_minutes} active minutes in a 24-hour period from first join. Completed, covered windows include players with zero activity.</p>
    </Panel>;
}

function Funnel({data}) {
    const steps = [['requested', 'Claimed'], ['accepted', 'Accepted'], ['qualified', 'Qualified'], ['rewarded', 'Reward delivered']];
    return <Panel title="Referral progress">
        <ol className="referrals-funnel">{steps.map(([key, label]) => <li key={key}>
            <span>{label}</span><strong>{number(data.funnel[key])}</strong>
            <div className="referrals-funnel-track" aria-hidden="true"><div style={{width: `${data.funnel.requested > 0 ? data.funnel[key] / data.funnel.requested * 100 : 0}%`}}/></div>
        </li>)}</ol>
        <h3 className="h6 mt-4">Current claim outcomes</h3>
        <dl className="referrals-outcomes">{[['verifying', 'Verifying'], ['pending', 'Pending'], ['rejected', 'Rejected'], ['expired', 'Expired']].map(([key, label]) =>
            <div key={key}><dt>{label}</dt><dd>{number(data.funnel[key])}</dd></div>)}</dl>
        <p className="small"><strong>Median claim to qualification: {number(data.funnel.median_qualification_hours)} hours</strong><br/>
            {number(data.funnel.qualification_sample)} observed completed claims; historical claims with uncertain timing are excluded. This is elapsed time, including time offline and awaiting approval.</p>
        <p className="small mb-0">All recorded claims, including those from before activity collection and later rejected or expired claims. Reward delivered means at least one reward has been confirmed. Qualification is the recorded approval time after screening and reward eligibility checks.</p>
    </Panel>;
}

function Credit({data}) {
    return <Panel title="Credit per retained referred player">
        <p className="small">Totals include all recorded historical awards. Each currency uses the same complete acquired-player cohort: recorded referrals with a complete days 7–13 window, including those who never qualified for a reward. Its numerator includes only that currency’s awards to the eligible cohort.</p>
        {!data.credit.length ? <p className="mb-0">No credit measures are available for a completed cohort yet.</p>
            : <div className="table-responsive" tabIndex="0" role="region" aria-label="Credit by historical currency">
                <table className="table table-sm referrals-table"><thead><tr>
                    <th scope="col">Currency</th><th scope="col">Total awarded</th><th scope="col">Total posted</th><th scope="col">Eligible cohort awarded</th>
                    <th scope="col">Returned / eligible</th><th scope="col">Awarded per returned player</th><th scope="col">Posted per returned player</th><th scope="col">Incomplete</th>
                </tr></thead><tbody>{data.credit.map(row => <tr key={row.currency}>
                    <th scope="row">{row.currency}</th><td>{amount(row.granted_minor, row.currency)}</td>
                    <td>{amount(row.delivered_minor, row.currency)}</td><td>{amount(row.cohort_granted_minor, row.currency)}</td><td>{number(row.retained_players)} / {number(row.eligible)}</td>
                    <td>{amount(row.granted_minor_per_retained, row.currency)}</td><td>{amount(row.delivered_minor_per_retained, row.currency)}</td><td>{number(row.incomplete)}</td>
                </tr>)}</tbody></table>
            </div>}
        <p className="small mt-3 mb-0">Awarded credit is the fixed reward owed; posted credit is confirmed in the referrer’s wallet. Currencies remain separate. Store credit is neither cash expenditure nor proven incremental return on investment. Changing reward settings never reprices these awards.</p>
    </Panel>;
}

function PostReward({data}) {
    return <Panel title="New-player return after referrer credit posting">
        <p className="small">At least {data.coverage.return_minimum_minutes} active minutes from 24 hours to 8 days after the referrer’s credit was actually posted. These reward-recipient cohorts are separate from the acquisition comparison.</p>
        {!data.credit.length ? <p className="mb-0">No posted-credit return observations are available yet.</p>
            : <div className="table-responsive" tabIndex="0" role="region" aria-label="New-player returns after credit posting">
                <table className="table table-sm referrals-table"><thead><tr>
                    <th scope="col">Credit currency</th><th scope="col">Returned / eligible</th><th scope="col">Return rate</th>
                    <th scope="col">Incomplete</th><th scope="col">Unknown posting time</th>
                </tr></thead><tbody>{data.credit.map(row => <tr key={row.currency}>
                    <th scope="row">{row.currency}</th><td>{number(row.post_reward.retained)} / {number(row.post_reward.eligible)}</td>
                    <td>{percent(row.post_reward.rate)}</td><td>{number(row.post_reward.incomplete)}</td>
                    <td>{number(row.post_reward.unknown_timing)}</td>
                </tr>)}</tbody></table>
            </div>}
        <p className="small mt-3 mb-0">Small samples are descriptive. Missing posting times and windows without complete activity coverage are excluded. Observation beyond the first 35 days after joining is unavailable; a late reward may leave this window incomplete.</p>
    </Panel>;
}

export const ReferralAnalytics = ({data}) => {
    const [mode, setMode] = useState('weekly');
    const windows = REFERRAL_WINDOWS[mode];
    const available = !['disabled', 'unavailable'].includes(data.coverage.status);
    return <section className="referrals-analytics" aria-label="Referral analytics">
        <Coverage data={data}/>
        {available && <>
            <Row className="g-3 mb-4">{REFERRAL_GROUPS.map(group => <Col sm={6} xl={3} key={group}>
                <Card className="h-100 shadow"><Card.Body><h2 className="h6">{GROUP_LABELS[group]}</h2>
                    <strong className="h3">{number(data.summary[group])}</strong><div className="small">of {number(data.summary.players)} observed players</div>
                </Card.Body></Card>
            </Col>)}</Row>
            <p className="small mb-4">Later claims are a subset of players with no referral in their first 24 hours. The groups overlap and do not add up to the total.</p>
            <div className="referrals-chart-toolbar mb-3">
                <Form.Group controlId="referrals-return-windows">
                    <Form.Label>Return windows</Form.Label><Form.Select value={mode} onChange={event => setMode(event.target.value)}>
                    <option value="weekly">Weekly returns</option><option value="daily">Exact D1 / D7 / D30</option>
                </Form.Select></Form.Group>
                <p className="small mb-0">Only complete windows enter each denominator. A dash means no complete sample.</p>
            </div>
            <Comparison data={data} windows={windows}/><Cohorts data={data} windows={windows}/>
            <Row><Col xl={8}><Activity data={data}/></Col><Col xl={4}><Funnel data={data}/></Col></Row>
            <Credit data={data}/>
            <PostReward data={data}/>
            <details className="card shadow mb-4 referrals-methodology"><summary>How to read these results</summary>
                <div className="card-body pt-0">
                    <p>Recorded referrals have a claim within {data.coverage.attribution_hours} hours of first join. Players who claim later remain in the original no-referral baseline and also appear in the later-claim subset. This analytics cutoff does not change the referral program’s active-playtime claim deadline.</p>
                    <p>No referral in the first 24 hours does not prove organic acquisition. Unknown attribution identifies insufficient collection coverage. Claims stay in acquisition groups even if qualification later fails.</p>
                    <p>Exact D1, D7 and D30 cover the 24-hour period starting 1, 7 or 30 days after first join. Weekly returns cover days 7–13, 14–20 and 28–34. There is no inferred historical backfill where precise active-time coverage is missing.</p>
                    <p className="mb-0">Compare the same first-join periods in the cohort table. Different cohorts, small samples and qualification requirements can affect results; successful referrals alone are not an unbiased acquisition comparison.</p>
                </div>
            </details>
        </>}
    </section>;
};

const ServerReferrals = () => {
    const {identifier} = useParams();
    const {allowed, authLoaded, data, error, isPending, isFetching, refetch} = useReferrals(identifier);
    if (!authLoaded) return <p role="status">Checking access…</p>;
    if (!allowed) return <Alert variant="warning">Referral analytics requires an authenticated account with explicit referral analytics access.</Alert>;
    return <LoadIn><div className="referrals-page">
        <div className="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-3">
            <div><h1 className="h3 mb-1">Referrals</h1><p className="mb-0">Acquisition, return activity and historical rewards.</p>
                {data && <small>Updated {date(data.generated_at)}</small>}</div>
            <Button variant="outline-secondary" onClick={() => refetch()} disabled={isFetching}>Refresh</Button>
        </div>
        {error && <Alert variant="warning" role="alert">{error.message} {data && 'Previously loaded results are hidden until a fresh response succeeds.'}</Alert>}
        {isPending && !error && <p role="status" aria-live="polite">Loading referral analytics…</p>}
        {data && !error && <ReferralAnalytics data={data}/>}
    </div></LoadIn>;
};

export default ServerReferrals;
