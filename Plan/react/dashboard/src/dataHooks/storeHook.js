import {useQuery} from '@tanstack/react-query';
import {useAuth} from '../hooks/authenticationHook.tsx';
import {useNavigation} from '../hooks/navigationHook.tsx';
import {baseAddress, staticSite} from '../service/backendConfiguration';
import {canViewStore, validateStore} from '../util/storeAnalytics.js';

export function useStore(identifier, days, currency) {
    const auth = useAuth();
    const {updateRequested} = useNavigation();
    const allowed = canViewStore(auth, staticSite);
    const query = useQuery({
        queryKey: ['private-store', auth.user?.username, identifier, days, currency, updateRequested],
        enabled: allowed && Boolean(identifier),
        queryFn: async ({signal}) => {
            const parameters = new URLSearchParams({server: identifier, days: String(days)});
            if (currency) parameters.set('currency', currency);
            const response = await fetch(`${baseAddress}/v1/store?${parameters}`, {
                credentials: 'same-origin', cache: 'no-store', signal, headers: {Accept: 'application/json'}
            });
            if (!response.ok) throw new Error(response.status === 401 || response.status === 403
                ? 'Your account does not have access to Store analytics.' : 'Store analytics could not be loaded.');
            const data = validateStore(await response.json());
            if (data.period.days !== days || data.period.currency !== (currency || null)) {
                throw new Error('Store analytics returned results for a different filter.');
            }
            return data;
        },
        retry: false, gcTime: 0, staleTime: 60000, refetchInterval: 60000
    });
    return {...query, data: allowed ? query.data : undefined, allowed, authLoaded: auth.authLoaded};
}
