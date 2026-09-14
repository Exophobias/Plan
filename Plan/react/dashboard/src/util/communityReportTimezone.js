export const REPORT_TIMEZONE = 'America/Vancouver';
export const REPORT_TIMEZONE_LABEL = 'Pacific Time (Vancouver)';

// B.C.'s final spring transition: https://news.gov.bc.ca/releases/2026AG0013-000209
// Pin the permanent offset after this instant so browsers with older tzdata agree with the server.
const PERMANENT_PACIFIC_FROM = Date.parse('2026-03-08T10:00:00Z');
const PACIFIC_OFFSET_MINUTES = -420;
const historicalCalendar = new Intl.DateTimeFormat('en-CA', {
    timeZone: REPORT_TIMEZONE, calendar: 'iso8601', numberingSystem: 'latn',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    second: '2-digit', hourCycle: 'h23'
});

function calendar(value) {
    const instant = new Date(value).getTime();
    if (instant >= PERMANENT_PACIFIC_FROM) {
        const wall = new Date(instant + PACIFIC_OFFSET_MINUTES * 60000).toISOString();
        return {date: wall.slice(0, 10), time: wall.slice(11, 16), offset: PACIFIC_OFFSET_MINUTES};
    }
    const parts = Object.fromEntries(historicalCalendar.formatToParts(instant)
        .filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
    const date = `${parts.year}-${parts.month}-${parts.day}`;
    const time = `${parts.hour}:${parts.minute}`;
    const wall = Date.parse(`${date}T${time}:${parts.second}Z`);
    return {date, time, offset: (wall - Math.floor(instant / 1000) * 1000) / 60000};
}

// Input is an instant, unlike the UTC-backed calendar arithmetic in communityReport.js.
export const reportDate = value => calendar(value).date;
export function reportTimestamp(value) {
    const {date, time, offset} = calendar(value);
    const absolute = Math.abs(offset);
    const hours = String(Math.floor(absolute / 60)).padStart(2, '0');
    const minutes = String(absolute % 60).padStart(2, '0');
    return `${date} ${time} UTC${offset < 0 ? '−' : '+'}${hours}:${minutes}`;
}
