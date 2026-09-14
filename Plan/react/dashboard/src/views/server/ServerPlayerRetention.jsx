import React from 'react';
import {useParams} from "react-router";
import {useAuth} from "../../hooks/authenticationHook.tsx";
import PlayerRetention from "../../components/cards/common/PlayerRetention.jsx";
import ReferralSummary from "../../components/cards/server/ReferralSummary.jsx";

const ServerPlayerRetention = () => {
    const {hasPermission} = useAuth();
    const {identifier} = useParams();

    const seeRetention = hasPermission('page.server.retention');
    return (
        <><ReferralSummary identifier={identifier}/>
            <PlayerRetention id={"server-retention"} identifier={identifier} seeRetention={seeRetention}/></>
    )
};

export default ServerPlayerRetention
