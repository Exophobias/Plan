import {useQuery} from '@tanstack/react-query';
import {useAuth} from '../hooks/authenticationHook.tsx';
import {useNavigation} from '../hooks/navigationHook.tsx';
import {baseAddress, staticSite} from '../service/backendConfiguration';
import {canViewReferrals, validateReferrals} from '../util/referralAnalytics.js';

export function useReferrals(identifier) {
    const auth = useAuth();
    const {updateRequested} = useNavigation();
    const allowed = canViewReferrals(auth, staticSite);
    const query = useQuery({
        queryKey: ['private-referrals', auth.user?.username, identifier, updateRequested],
        enabled: allowed && Boolean(identifier),
        queryFn: async ({signal}) => {
            const response = await fetch(`${baseAddress}/v1/referrals?server=${encodeURIComponent(identifier)}`, {
                credentials: 'same-origin', cache: 'no-store', signal, headers: {Accept: 'application/json'}
            });
            if (!response.ok) throw new Error(response.status === 401 || response.status === 403
                ? 'Your account does not have access to referral analytics.' : 'Referral analytics could not be loaded.');
            return validateReferrals(await response.json());
        },
        retry: false, gcTime: 0, staleTime: 60000, refetchInterval: 60000
    });
    // Never render retained query data after logout or permission removal.
    return {...query, data: allowed ? query.data : undefined, allowed, authLoaded: auth.authLoaded};
}
