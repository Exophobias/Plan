import {useQuery} from '@tanstack/react-query';
import {useAuth} from '../hooks/authenticationHook.tsx';
import {baseAddress, staticSite} from '../service/backendConfiguration';
import {canViewReports, validateCommunityReport} from '../util/communityReport.js';

export function useCommunityReport(identifier, request) {
    const auth = useAuth();
    const allowed = canViewReports(auth, staticSite);
    const query = useQuery({
        queryKey: ['private-community-report', auth.user?.username, identifier, request],
        enabled: allowed && Boolean(identifier) && Boolean(request),
        queryFn: async ({signal}) => {
            const parameters = new URLSearchParams({server: identifier, start: request.start, end: request.end});
            const response = await fetch(`${baseAddress}/v1/community-report?${parameters}`, {
                credentials: 'same-origin', cache: 'no-store', signal, headers: {Accept: 'application/json'}
            });
            if (!response.ok) throw new Error(response.status === 401 || response.status === 403
                ? 'Your account does not have access to community reports.' : 'The report could not be generated. Try again.');
            return validateCommunityReport(await response.json(), request);
        },
        retry: false, gcTime: 0, staleTime: Infinity, refetchOnWindowFocus: false, refetchOnReconnect: false
    });
    return {...query, data: allowed ? query.data : undefined, allowed, authLoaded: auth.authLoaded};
}
