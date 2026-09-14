import {useAuth} from "../../hooks/authenticationHook.tsx";
import {useMetadata} from "../../hooks/metadataHook.tsx";
import {Navigate} from "react-router";
import React, {useEffect, useState} from "react";
import {staticSite} from "../../service/backendConfiguration";
import ActionButton from "../input/button/ActionButton.tsx";
import {redirectLoadingState} from "../../util/redirectState.js";

const RedirectPlaceholder = ({failed = false}) => {
    const [redirectStart] = useState(Date.now())
    const [dateDiff, setDateDiff] = useState(0)

    useEffect(() => {
        const interval = setInterval(() => {
            if (redirectLoadingState(dateDiff, failed) === 'loading') {
                setDateDiff(Date.now() - redirectStart);
            } else {
                clearInterval(interval);
            }
        }, 500);
        return () => clearInterval(interval);
    }, [redirectStart, dateDiff, failed])

    const state = redirectLoadingState(dateDiff, failed);
    return <div className="m-4" style={{maxWidth: '500px'}}>
        <p role={failed ? 'alert' : 'status'}>{state === 'error'
            ? 'Your analytics could not be loaded. Please try again.'
            : state === 'delayed' ? 'Loading your analytics is taking longer than expected.' : 'Loading your analytics…'}</p>
        {state !== 'loading' && <ActionButton onClick={() => window.location.reload()}>Try again</ActionButton>}
    </div>;
}

const MainPageRedirect = () => {
    const {authLoaded, authRequired, loggedIn, user, hasPermission, loginError} = useAuth();
    const {isProxy, serverName, serverUUID, metadataError} = useMetadata();

    if (staticSite) {
        const urlParams = new URLSearchParams(window.location.search);
        const redirect = urlParams.get('redirect');
        if (redirect) {
            return (<Navigate to={redirect} replace={true}/>)
        }
    }

    if (loginError || metadataError) return <RedirectPlaceholder failed/>;

    if (!authLoaded || !serverName || !serverUUID) {
        return <RedirectPlaceholder/>
    }

    const redirectBasedOnPermissions = () => {
        if (isProxy && hasPermission('access.network')) {
            return (<Navigate to={"/network/overview"} replace={true}/>)
        } else if (hasPermission('access.server.' + serverUUID)) {
            return (<Navigate to={"/server/" + serverUUID + "/overview"}
                              replace={true}/>)
        } else if (hasPermission('access.player')) {
            return (<Navigate to={"/players"} replace={true}/>)
        } else if (hasPermission('access.player.self')) {
            return (<Navigate to={"/player/" + (user.playerUUID ? user.playerUUID : user.username)} replace={true}/>)
        }
    };

    if (authRequired && !loggedIn) {
        if (window.location.pathname.startsWith("/login")) {
            return (<Navigate to="/login" replace={true}/>)
        } else {
            return (<Navigate
                to={"/login?from=" + encodeURIComponent(window.location.pathname + window.location.search + window.location.hash)}
                replace={true}/>)
        }
    } else if (authRequired && loggedIn) {
        return redirectBasedOnPermissions();
    } else {
        return (<Navigate
            to={isProxy ? "/network/overview" : "/server/" + serverUUID + "/overview"}
            replace={true}/>)
    }
}

export default MainPageRedirect
