import {formatReportNumber, reportPeriodLabel, reportTimestamp} from './communityReport.js';

export const REPORT_WIDTH = 1080;
export const REPORT_HEIGHT = 1350;
const C = {background: '#101c2d', panel: '#192a40', gold: '#d8b86a', white: '#f5f1e6', muted: '#bdc8d5', grid: '#3d4e64'};

function font(ctx, size, weight = 400) { ctx.font = `${weight} ${size}px Arial, sans-serif`; }
function text(ctx, value, x, y, size = 26, color = C.white, weight = 400, maxWidth = 940) {
    ctx.fillStyle = color;
    font(ctx, size, weight);
    while (ctx.measureText(String(value)).width > maxWidth && size > 18) font(ctx, --size, weight);
    ctx.fillText(String(value), x, y);
}
function wrap(ctx, value, width, size = 23) {
    font(ctx, size);
    const lines = [];
    let line = '';
    for (const word of value.split(' ')) {
        const next = line ? `${line} ${word}` : word;
        if (line && ctx.measureText(next).width > width) { lines.push(line); line = word; } else line = next;
    }
    if (line) lines.push(line);
    return lines;
}
function paragraph(ctx, value, x, y, width, size = 23, color = C.muted, lineHeight = 29) {
    const lines = wrap(ctx, value, width, size);
    lines.forEach((line, i) => text(ctx, line, x, y + i * lineHeight, size, color));
    return lines.length * lineHeight;
}
function line(ctx, x1, y1, x2, y2, color = C.grid, width = 1) {
    ctx.strokeStyle = color; ctx.lineWidth = width; ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
}

function chart(ctx, daily, top, bottom) {
    text(ctx, daily.label, 70, top, 29, C.white, 700);
    const left = 144, right = 1008, y0 = top + 42, y1 = bottom - 40;
    const values = daily.points.map(row => row.value);
    const observedMax = Math.max(...values.filter(value => value !== null));
    const magnitude = Math.pow(10, Math.floor(Math.log10(observedMax || 1)));
    const ticks = daily.key === 'participants' ? Math.min(4, observedMax) : 4;
    const maximum = daily.key === 'participants' ? Math.ceil(observedMax / ticks) * ticks
        : Math.max(0.1, Math.ceil(observedMax / magnitude) * magnitude);
    for (let step = 0; step <= ticks; step++) {
        const value = maximum * step / ticks, y = y1 - (y1 - y0) * step / ticks;
        line(ctx, left, y, right, y);
        ctx.textAlign = 'right';
        text(ctx, formatReportNumber(value, daily.key === 'participants' ? 0 : maximum < 1 ? 3 : 1), left - 16, y + 8, 22, C.muted, 400, 75);
    }
    ctx.textAlign = 'left';
    const spacing = (right - left) / values.length;
    // Every point has its own position. Nulls leave gaps; genuine zeros stay at the baseline.
    for (let i = 0; i < values.length; i++) {
        if (values[i] === null) continue;
        const height = (y1 - y0) * values[i] / maximum;
        ctx.fillStyle = C.gold;
        ctx.fillRect(left + i * spacing + spacing * .12, y1 - Math.max(height, 2), Math.max(1, spacing * .76), Math.max(height, 2));
    }
    const indices = [...new Set([0, Math.floor((values.length - 1) / 2), values.length - 1])];
    indices.forEach((index, position) => {
        ctx.textAlign = position === 0 ? 'left' : position === indices.length - 1 ? 'right' : 'center';
        const x = position === 0 ? left : position === indices.length - 1 ? right : left + (index + .5) * spacing;
        text(ctx, daily.points[index].date.slice(5), x, y1 + 35, 23, C.muted);
    });
    ctx.textAlign = 'left';
}

function activity(ctx, page) {
    const notes = page.cards.map(card => card.definition);
    if (page.daily) {
        notes.push(page.daily.definition);
        if (page.daily.points.some(point => point.value === null)) notes.push('Gaps mean withheld or unavailable data.');
    }
    const noteHeight = notes.reduce((sum, value) => sum + wrap(ctx, value, 940, 22).length * 27 + 7, 0);
    const notesTop = 1254 - noteHeight;
    const rows = Math.ceil(page.cards.length / 2);
    const start = page.daily ? 320 : 380;
    const cardHeight = page.daily ? 150 : Math.min(290, (notesTop - start - 70) / Math.max(1, rows) - 18);
    page.cards.forEach((card, index) => {
        const columns = page.cards.length === 1 ? 1 : 2;
        const width = columns === 1 ? 940 : 460;
        const x = 70 + index % columns * 480, y = start + Math.floor(index / columns) * (cardHeight + 18);
        ctx.fillStyle = C.panel; ctx.fillRect(x, y, width, cardHeight);
        ctx.fillStyle = C.gold; ctx.fillRect(x, y, 4, cardHeight);
        text(ctx, card.label, x + 26, y + 43, 25, C.muted, 400, width - 52);
        text(ctx, formatReportNumber(card.value, card.key === 'active_hours' ? 1 : 0), x + 26,
            y + Math.min(cardHeight - 26, 125), 64, C.white, 700, width - 52);
    });
    if (page.daily) {
        const top = page.cards.length ? start + rows * (cardHeight + 18) + 36 : 370;
        chart(ctx, page.daily, top, notesTop - 42);
    }
    line(ctx, 70, notesTop - 30, 1010, notesTop - 30);
    let y = notesTop;
    notes.forEach(value => { y += paragraph(ctx, value, 70, y, 940, 22, C.muted, 27) + 7; });
}

function returning(ctx, page) {
    const r = page.retention;
    text(ctx, 'A reason to return', 70, 385, 34, C.white, 700);
    text(ctx, `${formatReportNumber(r.rate * 100, 1)}%`, 70, 575, 150, C.gold, 700);
    text(ctx, 'returned in their first week', 77, 643, 40, C.white);
    ctx.fillStyle = C.panel; ctx.fillRect(70, 715, 940, 30);
    ctx.fillStyle = C.gold; ctx.fillRect(70, 715, 940 * r.rate, 30);
    text(ctx, `${formatReportNumber(r.returned)} of ${formatReportNumber(r.eligible)} eligible accounts`, 70, 810, 32, C.white, 700);
    paragraph(ctx, 'Time to settle in. Familiar faces to come back to.', 70, 935, 930, 32, C.white, 41);
    line(ctx, 70, 1054, 1010, 1054);
    const height = paragraph(ctx, page.definition, 70, 1100, 940, 25, C.muted, 33);
    text(ctx, `Return observations through ${reportTimestamp(r.observed_through)}.`, 70, 1100 + height + 18, 23, C.muted);
}

function busyTimes(ctx, page) {
    text(ctx, 'Average active players', 70, 348, 32, C.white, 700);
    paragraph(ctx, page.definition, 70, 390, 940, 24, C.muted, 31);
    const left = 150, top = 492, column = 860 / 24, row = 72, width = column - 3, height = row - 9;
    const maximum = Math.max(...page.busy_times.cells.map(cell => cell.average_active_players || 0));
    const colour = value => {
        if (!value) return C.panel;
        const stops = [[11, 93, 59], [18, 128, 71], [34, 197, 94], [134, 239, 172], [220, 252, 231]];
        const position = Math.max(0, Math.min(1, value / maximum)) * (stops.length - 1);
        const index = Math.min(stops.length - 2, Math.floor(position));
        const fraction = position - index, low = stops[index], high = stops[index + 1];
        return `rgb(${low.map((component, componentIndex) => Math.round(component + (high[componentIndex] - component) * fraction)).join(',')})`;
    };
    const withheld = (x, y, w, h) => {
        ctx.fillStyle = C.background; ctx.fillRect(x, y, w, h);
        ctx.strokeStyle = '#7b8b9e'; ctx.lineWidth = 1; ctx.strokeRect(x + .5, y + .5, w - 1, h - 1);
        ctx.save(); ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
        for (let offset = -h; offset < w; offset += 10) line(ctx, x + offset, y + h, x + offset + h, y, C.grid);
        ctx.restore();
    };
    text(ctx, 'Hour · Pacific Time (Vancouver)', left, 459, 23, C.muted);
    for (let hour = 0; hour < 24; hour += 3) {
        ctx.textAlign = 'center'; text(ctx, String(hour).padStart(2, '0'), left + (hour + .5) * column, 480, 20, C.muted);
    }
    ctx.textAlign = 'left';
    ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].forEach((day, index) => text(ctx, day, 70, top + index * row + 40, 25, C.muted));
    page.busy_times.cells.forEach(cell => {
        const x = left + cell.hour * column, y = top + (cell.day - 1) * row;
        if (cell.average_active_players === null) withheld(x, y, width, height);
        else { ctx.fillStyle = colour(cell.average_active_players); ctx.fillRect(x, y, width, height); }
    });
    const scaleLeft = 150, scaleTop = 1034, scaleWidth = 500;
    for (let pixel = 0; pixel < scaleWidth; pixel++) {
        ctx.fillStyle = colour(maximum * (pixel + 1) / scaleWidth); ctx.fillRect(scaleLeft + pixel, scaleTop, 1, 22);
    }
    text(ctx, 'Lower', scaleLeft, scaleTop + 52, 22, C.muted);
    ctx.textAlign = 'right'; text(ctx, formatReportNumber(maximum, 2) + ' average players', scaleLeft + scaleWidth,
        scaleTop + 52, 22, C.muted); ctx.textAlign = 'left';
    ctx.fillStyle = C.panel; ctx.fillRect(70, 1120, 30, 24);
    text(ctx, 'Recorded quiet', 113, 1140, 23, C.muted);
    withheld(430, 1120, 30, 24); text(ctx, 'Withheld or unavailable', 473, 1140, 23, C.muted);
    paragraph(ctx, 'Brighter cells show more activity. Each column is one hour, from 00 through 23.', 70, 1192, 940, 23, C.muted, 29);
}

// Preview and download share this exact raster. No screenshots, external fonts or image dependencies.
export function drawCommunityReport(canvas, report, pageIndex) {
    const page = report.pages[pageIndex];
    if (!page) throw new Error('No report page to render.');
    canvas.width = REPORT_WIDTH; canvas.height = REPORT_HEIGHT;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('This browser cannot render report images.');
    ctx.fillStyle = C.background; ctx.fillRect(0, 0, REPORT_WIDTH, REPORT_HEIGHT);
    ctx.fillStyle = C.gold; ctx.fillRect(0, 0, REPORT_WIDTH, 8);
    text(ctx, 'PATRIAM', 70, 85, 31, C.gold, 700);
    ctx.textAlign = 'right'; text(ctx, 'COMMUNITY CHRONICLE', 1010, 83, 23, C.muted); ctx.textAlign = 'left';
    text(ctx, page.title, 70, 176, 58, C.white, 700);
    text(ctx, reportPeriodLabel(report.period), 70, 231, 28, C.muted);
    line(ctx, 70, 271, 1010, 271, C.gold, 2);
    if (page.id === 'activity') activity(ctx, page);
    else if (page.id === 'busy-times') busyTimes(ctx, page);
    else returning(ctx, page);
    text(ctx, `${report.period.complete ? 'Recorded' : 'Partial period · recorded'} ${reportTimestamp(report.period.recorded_from)}`, 70, 1280, 22, C.muted);
    text(ctx, `through ${reportTimestamp(report.period.as_of)}`, 70, 1310, 22, C.muted);
    ctx.textAlign = 'right'; text(ctx, `${pageIndex + 1} / ${report.pages.length}`, 1010, 1302, 21, C.muted); ctx.textAlign = 'left';
}

export async function renderCommunityReport(report) {
    return Promise.all(report.pages.map(async (_, index) => {
        const canvas = document.createElement('canvas');
        drawCommunityReport(canvas, report, index);
        return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob)
            : reject(new Error('The report image could not be created.')), 'image/png'));
    }));
}
