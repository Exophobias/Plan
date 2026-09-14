import React, {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router';
import {Alert, Badge, Button, Card, Col, Form, Row} from 'react-bootstrap';
import {useStore} from '../../dataHooks/storeHook.js';
import {originLabel, periodChange, statusLabel, storeMoney, storeSeries} from '../../util/storeAnalytics.js';
import LoadIn from '../../components/animation/LoadIn.tsx';
import Graph from '../../components/graphs/Graph.jsx';
import '../../style/store.css';

const number = value => value === null || value === undefined ? '—' : value.toLocaleString();
const date = value => value ? new Date(value).toLocaleString() : 'Not yet recorded';
const day = value => new Date(value).toISOString().slice(0, 10);
const Panel = ({title, children}) => <Card className="shadow mb-4"><Card.Header><h2 className="h6 mb-0">{title}</h2></Card.Header>
    <Card.Body>{children}</Card.Body></Card>;
const Table = ({label, children}) => <div className="table-responsive" tabIndex="0" role="region" aria-label={label}>
    <table className="table table-sm store-table">{children}</table></div>;

function Coverage({data}) {
    const {coverage: c, period: p} = data;
    const states = {
        current: ['success', 'Up to date'], collecting: ['info', 'Collecting'], stale: ['warning', 'Delayed'],
        paused: ['warning', 'Collection paused'], unavailable: ['secondary', 'Unavailable']
    };
    const [variant, label] = states[c.status];
    return <>
        <p className="small store-meta mb-2"><Badge bg={variant} text={variant === 'warning' ? 'dark' : undefined}>{label}</Badge>
            {' · '}Store records through {date(c.source_as_of)}{' · '}Tracking started {date(c.capture_started_at)}</p>
        {c.status === 'unavailable' && <Alert variant="secondary" role="status">Store statistics are not available yet. This page will populate when collection is enabled and the first import completes.</Alert>}
        {c.status === 'stale' && <Alert variant="warning" role="status">The Store feed is delayed. Results stop at the last confirmed update.</Alert>}
        {c.status === 'paused' && <Alert variant="warning" role="status">Collection is paused. Previously confirmed results are shown through the time above.</Alert>}
        {c.status !== 'unavailable' && !p.complete && <p className="small mb-3">This period has incomplete coverage. Totals include recorded activity only; trend lines leave incomplete days blank and comparisons are withheld.</p>}
    </>;
}

function Metrics({data}) {
    const {summary: s, previous_summary: previous, period: p} = data;
    const metrics = [
        ['saved_orders', 'Saved orders', number(s.saved_orders), 'Includes unpaid orders'],
        ['order_amount_minor', 'Saved order value', storeMoney(s.order_amount_minor, p.currency),
            p.currency ? 'Final order prices, including credit-funded amounts' : 'Select a currency to see amounts'],
        ['known_payers', 'Ordering customers', number(s.known_payers), 'Distinct known buyers; gift recipients are excluded'],
        ['repeat_payers', 'Repeat ordering customers', number(s.repeat_payers), 'Customers with at least two saved orders in this period']
    ];
    return <Row className="g-3 mb-4">{metrics.map(([key, title, value, description]) => {
        const change = periodChange(s[key], previous[key], p.complete, p.previous_complete);
        return <Col sm={6} xl={3} key={key}><Card className="shadow h-100"><Card.Body>
            <h2 className="h6">{title}</h2><div className="store-metric">{value}</div>
            <p className="small mt-2 mb-2">{description}</p>
            {change !== null && <small>{change > 0 ? '+' : ''}{(change * 100).toFixed(1)}% vs previous {p.days} days</small>}
        </Card.Body></Card></Col>;
    })}</Row>;
}

function Trends({data}) {
    const [measure, setMeasure] = useState('counts');
    const monetary = measure === 'amounts' && Boolean(data.period.currency);
    const options = useMemo(() => ({
        title: {text: ''}, chart: {type: 'line', animation: false}, credits: {enabled: false},
        xAxis: {categories: data.daily.map(row => row.date), labels: {step: data.period.days === 90 ? 14 : 5,
            formatter: function () { return String(this.value).slice(5); }}, title: {text: 'Complete UTC days'}},
        responsive: {rules: [{condition: {maxWidth: 450}, chartOptions: {xAxis: {labels: {step: Math.ceil(data.period.days / 2)}}}}]},
        yAxis: {min: 0, allowDecimals: monetary, title: {text: monetary ? data.period.currency : 'Recorded count'}},
        plotOptions: {series: {animation: false}, line: {marker: {enabled: false}}},
        tooltip: {shared: true, useHTML: false, valueDecimals: monetary ? 2 : 0,
            valuePrefix: monetary ? `${data.period.currency} ` : ''},
        accessibility: {description: 'Saved orders and payment completions recorded each day. Gaps mean incomplete coverage. Exact values are available in the daily table.'},
        series: storeSeries(data.daily, monetary)
    }), [data.daily, data.period.currency, data.period.days, monetary]);
    return <Panel title="Order and payment activity">
        <div className="store-toolbar mt-0"><Form.Group controlId="store-trend-measure" className="form-group">
            <Form.Label>Measure</Form.Label><Form.Select value={monetary ? 'amounts' : 'counts'} onChange={event => setMeasure(event.target.value)}>
                <option value="counts">Recorded counts</option><option value="amounts" disabled={!data.period.currency}>Amounts by currency</option>
            </Form.Select></Form.Group></div>
        {data.daily.some(row => row.covered) ? <Graph id="store-activity-chart" options={options}/>
            : <p className="store-empty" role="status">No complete days have been collected in this period yet.</p>}
        <p className="small mt-3">Orders are counted when saved. Completion transitions count each recorded change into completed status, including a later recompletion. They may belong to earlier orders and include staff, API, free and credit-only orders.</p>
        <details><summary>Daily records and adjustments</summary><Table label="Daily Store records">
            <thead><tr><th scope="col">UTC date</th><th scope="col">Coverage</th><th scope="col">Orders</th>
                <th scope="col">Completion transitions</th><th scope="col">Order value</th><th scope="col">Completion value</th>
                <th scope="col">Recorded refunds</th><th scope="col">Recorded reversals</th><th scope="col">Credit issued</th><th scope="col">Credit captured</th></tr></thead>
            <tbody>{[...data.daily].reverse().map(row => <tr key={row.date}><th scope="row">{row.date}</th>
                <td>{row.covered ? 'Complete' : 'Incomplete'}</td><td>{row.covered ? number(row.saved_orders) : '—'}</td>
                <td>{row.covered ? number(row.completed_payments) : '—'}</td>
                {['order_amount_minor', 'completed_amount_minor', 'refund_minor', 'reversal_minor', 'credit_awarded_minor', 'credit_captured_minor']
                    .map(key => <td key={key}>{row.covered ? storeMoney(row[key], data.period.currency) : '—'}</td>)}
            </tr>)}</tbody></Table></details>
    </Panel>;
}

function Products({data}) {
    return <Panel title="Products in saved orders">
        <p className="small">Saved orders in the selected period, including unpaid orders. Product names and prices use recorded order facts.</p>
        {!data.products.length ? <p className="mb-0">No product records in this period.</p> : <Table label="Products in saved orders">
            <thead><tr><th scope="col">Product</th><th scope="col">Currency</th><th scope="col">Quantity</th><th scope="col">Orders</th><th scope="col">Order value</th></tr></thead>
            <tbody>{data.products.map((row, index) => <tr key={`${row.currency}:${row.name}:${index}`}>
                <th scope="row" className="store-text">{row.name}</th><td>{row.currency || 'Unknown'}</td>
                <td>{number(row.quantity)}</td><td>{number(row.order_count)}</td><td>{storeMoney(row.amount_minor, row.currency)}</td>
            </tr>)}</tbody></Table>}
        {data.products.length === 50 && <p className="small mb-0">Showing the top 50 product records.</p>}
    </Panel>;
}

function Snapshot({data}) {
    const paymentRows = data.currencies.flatMap(row => row.payments.map(payment => ({...payment, currency: row.currency})));
    return <>
        <h2 className="h4 mt-4 mb-2">Current Store snapshot</h2>
        <p className="small mb-3">All imported records as of {date(data.coverage.source_as_of)}, including the historical baseline.
            {' '}The currency filter applies; the date filter applies to activity above.</p>
        <Panel title="Payment statuses">
            {!paymentRows.length ? <p className="mb-0">No payment records imported.</p> : <Table label="Current payment status by origin and currency">
                <thead><tr><th scope="col">Status</th><th scope="col">Origin</th><th scope="col">Currency</th><th scope="col">Payments</th><th scope="col">Recorded value</th><th scope="col">Unknown amounts</th></tr></thead>
                <tbody>{paymentRows.map(row => <tr key={`${row.currency}:${row.status}:${row.origin}`}>
                    <th scope="row">{statusLabel(row.status)}</th><td className="store-text">{originLabel(row.origin)}</td>
                    <td>{row.currency || 'Unknown'}</td><td>{number(row.count)}</td><td>{storeMoney(row.amount_minor, row.currency)}</td><td>{number(row.unknown_amount_count)}</td>
                </tr>)}</tbody></Table>}
            <p className="small mb-0 mt-2">These are the latest recorded statuses, not a settlement or payout report. Later refunds can move a payment out of completed status.</p>
        </Panel>
        <Panel title="Recorded refunds and reversals">
            {!data.coverage.adjustments_complete && <p className="small">Adjustment history is incomplete across payment providers. These totals include only recorded adjustment amounts; missing records are not evidence of no refunds.</p>}
            {!data.currencies.length ? <p className="mb-0">No adjustment records imported.</p> : <Table label="Recorded adjustment amounts by currency">
                <thead><tr><th scope="col">Currency</th><th scope="col">Adjustments</th><th scope="col">Refunds</th><th scope="col">Reversals</th></tr></thead>
                <tbody>{data.currencies.map(row => <tr key={row.currency || 'unknown'}><th scope="row">{row.currency || 'Unknown'}</th>
                    <td>{number(row.adjustments.count)}</td><td>{storeMoney(row.adjustments.refund_minor, row.currency)}</td><td>{storeMoney(row.adjustments.reversal_minor, row.currency)}</td></tr>)}</tbody>
            </Table>}
        </Panel>
        <Panel title="Store credit">
            {!data.currencies.length ? <p className="mb-0">No credit records imported.</p> : <Table label="Store credit awards and order funding by currency">
                <thead><tr><th scope="col">Currency</th><th scope="col">Issued awards</th><th scope="col">Reserved</th><th scope="col">Captured</th><th scope="col">Released</th><th scope="col">Refunded</th></tr></thead>
                <tbody>{data.currencies.map(row => <tr key={row.currency || 'unknown'}><th scope="row">{row.currency || 'Unknown'}</th>
                    {['awarded_minor', 'reserved_minor', 'captured_minor', 'released_minor', 'refunded_minor'].map(key => <td key={key}>{storeMoney(row.credits[key], row.currency)}</td>)}
                </tr>)}</tbody></Table>}
            <p className="small mb-0 mt-2">Issued awards and current order-funding states are separate measures. Credit is not cash income, and these columns do not calculate the outstanding wallet balance.</p>
        </Panel>
    </>;
}

export function StoreAnalytics({data}) {
    return <section className="store-analytics" aria-label="Store analytics"><Coverage data={data}/>
        {data.coverage.status !== 'unavailable' && <>
            <Metrics data={data}/><Trends data={data}/><Products data={data}/><Snapshot data={data}/>
            <details className="card shadow mb-4"><summary>Definitions and data coverage</summary><div className="card-body pt-0">
                <p>Activity covers {day(data.period.from)} through {day(data.period.until - 1)} UTC. Today is excluded.
                    {' '}Period comparisons require two fully covered periods and a non-zero prior value. Saved orders are not completed sales.</p>
                <p>Historical baseline: {number(data.summary.baseline_orders)} orders. Their original payment transition dates, buyer links or currency may be unknown.
                    {' '}They appear in the current snapshot but do not become new activity when imported. {number(data.summary.unknown_currency_orders)} new orders in this period have unknown currency.</p>
                <p>Customers are distinct recorded buyers. Gifts count toward the buyer, not the recipient. Repeat ordering means at least two saved orders in the selected period; it does not require payment.</p>
                <p className="mb-0">Currencies remain separate. Order prices are final recorded prices; pre-discount gross sales, processor fees, profit and payouts are not calculated. The report contains aggregate statistics and does not list customer identities.</p>
            </div></details>
        </>}
    </section>;
}

export default function ServerStore() {
    const {identifier} = useParams();
    const [days, setDays] = useState(30);
    const [currency, setCurrency] = useState(null);
    const [currencies, setCurrencies] = useState([]);
    const {allowed, authLoaded, data, error, isPending, isFetching, refetch} = useStore(identifier, days, currency);
    useEffect(() => {
        if (!data) return;
        setCurrencies(data.available_currencies);
        if (currency === null && data.available_currencies.length) setCurrency(data.available_currencies[0]);
    }, [data, currency]);
    useEffect(() => { setCurrency(null); setCurrencies([]); }, [identifier]);
    if (!authLoaded) return <p role="status">Checking access…</p>;
    if (!allowed) return <Alert variant="warning">Store analytics requires an authenticated account with explicit Store analytics access.</Alert>;
    return <LoadIn><div className="store-page">
        <div className="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-3">
            <div><h1 className="h3 mb-1">Store</h1><p className="mb-0">Orders, payment records and store credit.</p>
                {data && !error && <small>Updated {date(data.generated_at)}</small>}</div>
            <Button variant="outline-secondary" onClick={() => refetch()} disabled={isFetching}>Refresh</Button>
        </div>
        <div className="store-toolbar">
            <Form.Group controlId="store-period" className="form-group"><Form.Label>Activity period</Form.Label>
                <Form.Select value={days} onChange={event => setDays(Number(event.target.value))}>
                    <option value={30}>Last 30 complete days</option><option value={90}>Last 90 complete days</option>
                </Form.Select></Form.Group>
            <Form.Group controlId="store-currency" className="form-group"><Form.Label>Currency</Form.Label>
                <Form.Select value={currency || ''} onChange={event => setCurrency(event.target.value)}>
                    <option value="">All currencies · counts only</option>{currencies.map(code => <option key={code} value={code}>{code}</option>)}
                </Form.Select></Form.Group>
        </div>
        {error && <Alert variant="warning" role="alert">{error.message} Previously loaded results are hidden until a fresh response succeeds.</Alert>}
        {isPending && !error && <p role="status" aria-live="polite">Loading Store analytics…</p>}
        {data && !error && <StoreAnalytics data={data}/>}
    </div></LoadIn>;
}
