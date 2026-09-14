import React from 'react';
import {Link} from 'react-router';
import {useReferrals} from '../../../dataHooks/referralsHook.js';

const ReferralSummary = ({identifier}) => {
    const {allowed, data, error, isPending} = useReferrals(identifier);
    if (!allowed) return null;
    return <aside className="card shadow mb-4" aria-label="Referral analytics summary">
        <div className="card-body d-flex flex-wrap align-items-center justify-content-between gap-3">
            <div><strong>Referral acquisition</strong>
                <div className="small">{isPending ? 'Loading referral summary…' : error ? 'Referral summary is unavailable.'
                    : data?.coverage.status === 'unavailable' || data?.coverage.status === 'disabled'
                        ? 'Referral collection is unavailable.'
                        : <>{data.summary.recorded_referral.toLocaleString()} recorded referrals · {data.summary.late_referral.toLocaleString()} later claims
                            {data.coverage.status !== 'ready' && ` · ${data.coverage.status === 'stale' ? 'Data is stale' : 'Collecting observations'}`}</>}
                </div>
            </div>
            <Link className="btn btn-outline-secondary" to={`/server/${encodeURIComponent(identifier)}/referrals`}>View referral analytics</Link>
        </div>
    </aside>;
};

export default ReferralSummary;
