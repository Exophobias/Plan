import React, {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router';
import {Alert, Button, Card, Form} from 'react-bootstrap';
import {useCommunityReport} from '../../dataHooks/communityReportHook.js';
import {availableReportSections, createPublicReport, publicReportText, REPORT_SECTIONS, reportPreset,
    reportRange, reportTimestamp, utcDate} from '../../util/communityReport.js';
import {renderCommunityReport} from '../../util/communityReportCanvas.js';
import '../../style/reports.css';

function ReportPreview({report}) {
    const [rendered, setRendered] = useState(null);
    useEffect(() => {
        let cancelled = false;
        const urls = [];
        const urlFor = blob => { const url = URL.createObjectURL(blob); urls.push(url); return url; };
        renderCommunityReport(report).then(blobs => {
            if (cancelled) return;
            const images = blobs.map(urlFor);
            const text = urlFor(new Blob([publicReportText(report)], {type: 'text/plain;charset=utf-8'}));
            const json = urlFor(new Blob([JSON.stringify(report, null, 2) + '\n'], {type: 'application/json'}));
            setRendered({report, images, text, json});
        }).catch(() => {
            if (!cancelled) setRendered({report, error: 'The images could not be rendered. Generate the report again.'});
        });
        return () => { cancelled = true; urls.forEach(url => URL.revokeObjectURL(url)); };
    }, [report]);
    if (rendered?.report !== report) return <p role="status">Preparing the PNG preview…</p>;
    if (rendered.error) return <Alert variant="warning" role="alert">{rendered.error}</Alert>;
    const filename = `patriam-community-${report.period.start_date}-to-${utcDate(Date.parse(report.period.end_date + 'T00:00:00Z') - 86400000)}`;
    return <section aria-label="Public report preview">
        <div className="reports-downloads mb-3">
            <a className="btn btn-outline-secondary" href={rendered.text} download={`${filename}.txt`}>Download public text</a>
            <a className="btn btn-outline-secondary" href={rendered.json} download={`${filename}.json`}>Download matching data</a>
        </div>
        <p className="small">These previews are the exact 1080 × 1350 PNG files. Download the selected images and announcement text when ready to share.</p>
        <div className="reports-previews">{report.pages.map((page, index) => <figure key={page.id}>
            <img src={rendered.images[index]} width="1080" height="1350" alt={`Public report page ${index + 1}: ${page.title}. Full text is available below.`}/>
            <figcaption className="mt-2 d-flex align-items-center justify-content-between gap-2">
                <span>Page {index + 1} · {page.title}</span>
                <a className="btn btn-primary" href={rendered.images[index]} download={`${filename}-${index + 1}.png`}>Download PNG {index + 1}</a>
            </figcaption>
        </figure>)}</div>
        <details className="mt-3"><summary>Public announcement text</summary><pre className="reports-public-text mt-3">{publicReportText(report)}</pre></details>
    </section>;
}

function Reports({identifier}) {
    const [preset, setPreset] = useState('month');
    const [dates, setDates] = useState(() => reportPreset('month'));
    const [request, setRequest] = useState(null);
    const [inputError, setInputError] = useState('');
    const [enabled, setEnabled] = useState({});
    const {allowed, authLoaded, data, error, isFetching} = useCommunityReport(identifier, request);
    const visible = data && request && !error && !isFetching;
    const report = useMemo(() => visible ? createPublicReport(data, enabled) : null, [visible, data, enabled]);
    const available = visible ? availableReportSections(data) : {};
    const changeDates = values => { setDates(values); setRequest(null); setInputError(''); };
    const generate = event => {
        event.preventDefault();
        try {
            const range = reportRange(dates.start, dates.end);
            setInputError(''); setEnabled({}); setRequest({...range, generated: Date.now()});
        } catch (failure) { setInputError(failure.message); }
    };
    if (!authLoaded) return <p role="status">Checking access…</p>;
    if (!allowed) return <Alert variant="warning">Community reports require an authenticated account with explicit Reports access.</Alert>;
    return <div className="reports-page">
        <h1 className="h3">Reports</h1>
        <p>Create a community recap for Discord from recorded player activity. Review the images here before sharing them.</p>
        <Card className="shadow mb-4"><Card.Body>
            <Form onSubmit={generate}>
                <div className="reports-controls">
                    <Form.Group controlId="report-preset"><Form.Label>Period</Form.Label>
                        <Form.Select value={preset} onChange={event => {
                            setPreset(event.target.value);
                            if (event.target.value !== 'custom') changeDates(reportPreset(event.target.value));
                        }}>
                            <option value="month">Last complete month</option><option value="yesterday">Yesterday</option><option value="custom">Custom dates</option>
                        </Form.Select></Form.Group>
                    <Form.Group controlId="report-start"><Form.Label>Start date · UTC</Form.Label>
                        <Form.Control type="date" required value={dates.start} max={dates.end || utcDate(Date.now())}
                            onChange={event => { setPreset('custom'); changeDates({...dates, start: event.target.value}); }}/></Form.Group>
                    <Form.Group controlId="report-end"><Form.Label>End date · UTC</Form.Label>
                        <Form.Control type="date" required value={dates.end} min={dates.start} max={utcDate(Date.now())}
                            onChange={event => { setPreset('custom'); changeDates({...dates, end: event.target.value}); }}/></Form.Group>
                    <Button type="submit" disabled={isFetching}>{isFetching ? 'Generating…' : 'Generate preview'}</Button>
                </div>
                <p className="small mb-0 mt-3">Both dates are included. Choose up to 366 days. Today produces a partial report through the available data cutoff.</p>
                {inputError && <Alert variant="warning" className="mt-3 mb-0" role="alert">{inputError}</Alert>}
            </Form>
        </Card.Body></Card>
        {isFetching && <p role="status" aria-live="polite">Checking recorded activity and preparing the report…</p>}
        {error && <Alert variant="warning" role="alert">{error.message}</Alert>}
        {visible && <>
            <p className="small">Activity recorded {reportTimestamp(data.period.recorded_from)} through {reportTimestamp(data.period.as_of)}. {data.period.complete ? 'The selected period is complete.' : 'This period has partial coverage; the images show the recorded dates.'}</p>
            {Object.values(available).some(Boolean) && <fieldset className="reports-sections mb-4">
                <legend className="h6">Include in the public report</legend>
                {Object.entries(REPORT_SECTIONS).filter(([key]) => available[key]).map(([key, label]) => <Form.Check
                    id={`report-section-${key}`} key={key} type="checkbox" label={label} checked={enabled[key] !== false}
                    onChange={event => setEnabled(previous => ({...previous, [key]: event.target.checked}))}/>)}
            </fieldset>}
            {report.pages.length > 0 ? <ReportPreview report={report}/> : <Alert variant="secondary" role="status">
                {Object.values(available).some(Boolean) ? 'Select a section to create your report.'
                    : 'There is no publishable report for this period yet. Try another date range as more activity is recorded.'}
            </Alert>}
            <details className="reports-private mt-4 mb-4"><summary>Operator notes · excluded from every download</summary>
                <p className="mt-3">Unavailable, small-group and zero summary sections are omitted automatically. Review the combined report before publishing, especially alongside earlier reports with overlapping dates.</p>
                {data.omissions.length > 0 ? <ul>{data.omissions.map((item, index) => <li key={`${item.section}-${index}`}><strong>{item.section}:</strong> {item.reason}</li>)}</ul>
                    : <p>No additional omissions were reported.</p>}
                <p className="mb-0">Activity and first-week returns use distinct account records. Newly observed players are first seen since community tracking began. Recent or incompletely observed first-week cohorts are excluded. Historical reports are limited to recorded history.</p>
            </details>
        </>}
    </div>;
}

export default function ServerReports() {
    const {identifier} = useParams();
    return <Reports key={identifier} identifier={identifier}/>;
}
