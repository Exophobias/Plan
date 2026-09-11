import React, {useCallback, useEffect, useState} from 'react';

import crest from '../../assets/patriam/crest.webp';
import landscape from '../../assets/patriam/eot-misty-valley.webp';
import {Alert} from "react-bootstrap";
import {Link, useNavigate} from "react-router";
import {useTranslation} from "react-i18next";
import {FontAwesomeIcon as Fa} from "@fortawesome/react-fontawesome";
import {faPalette} from "@fortawesome/free-solid-svg-icons";
import {useTheme} from "../../hooks/themeHook.tsx";
import ColorSelectorModal from "../../components/modal/ColorSelectorModal";
import {fetchLogin, fetchForumSignIn} from "../../service/authenticationService";
import {baseAddress} from "../../service/backendConfiguration";
import ForgotPasswordModal from "../../components/modal/ForgotPasswordModal";
import {useAuth} from "../../hooks/authenticationHook.tsx";
import ActionButton from "../../components/input/button/ActionButton.tsx";

const LoginForm = ({login}) => {
    const {t} = useTranslation();

    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');

    const onLogin = useCallback(async event => {
        event.preventDefault();
        if (!await login(username, password)) setPassword('');
    }, [username, password, setPassword, login]);

    return (
        <form className="user patriam-login-form" onSubmit={onLogin}>
            <div className="mb-3">
                <label htmlFor="inputUser">{t('html.login.username')}</label>
                <input autoComplete="username" className="form-control form-control-user"
                       id="inputUser"
                       placeholder={t('html.login.username')} type="text"
                       value={username} onChange={event => setUsername(event.target.value)}/>
            </div>
            <div className="mb-3">
                <label htmlFor="inputPassword">{t('html.login.password')}</label>
                <input autoComplete="current-password" className="form-control form-control-user"
                       id="inputPassword" placeholder={t('html.login.password')} type="password"
                       value={password} onChange={event => setPassword(event.target.value)}/>
            </div>
            <ActionButton className="btn-user w-100" id="login-button" onClick={onLogin}>
                {t('html.login.login')}
            </ActionButton>
        </form>
    );
}

const ColorChooserButton = () => {
    const {t} = useTranslation();
    const {toggleColorChooser} = useTheme();

    return (
        <div className='patriam-theme-control'>
            <button className="btn col-theme" onClick={toggleColorChooser}
                    title={t('html.label.themeSelect')} aria-label={t('html.label.themeSelect')}>
                <Fa icon={faPalette}/> <span>Appearance</span>
            </button>
        </div>
    )
}
const ForgotPasswordButton = ({onClick}) => {
    const {t} = useTranslation();

    return (
        <div className='text-center'>
            <button type="button" className='patriam-text-button small' onClick={onClick}>{t('html.login.forgotPassword')}</button>
        </div>
    )
}

const CreateAccountLink = () => {
    const {t} = useTranslation();

    return (
        <div className='text-center'>
            <Link to='/register' className='col-theme small'>{t('html.login.register')}</Link>
        </div>
    )
}

const LoginPage = () => {
    const {t} = useTranslation();
    const navigate = useNavigate();
    const {authLoaded, authRequired, loggedIn, updateLoginDetails} = useAuth();

    const [forgotPasswordModalOpen, setForgotPasswordModalOpen] = useState(false);
    const [forumSignIn, setForumSignIn] = useState(false);

    const [successMessage, setSuccessMessage] = useState('')
    const [failMessage, setFailMessage] = useState('');
    const [redirectTo, setRedirectTo] = useState(undefined);

    const togglePasswordModal = useCallback(() => setForgotPasswordModalOpen(!forgotPasswordModalOpen),
        [setForgotPasswordModalOpen, forgotPasswordModalOpen])

    useEffect(() => {
        document.body.classList.add("patriam-login-page");
        document.title = 'Patriam | Player analytics';

        const urlParams = new URLSearchParams(window.location.search);
        const cameFrom = urlParams.get('from');
        if (cameFrom) setRedirectTo(cameFrom);

        const registerSuccess = urlParams.get('registerSuccess');
        if (registerSuccess) setSuccessMessage(t('html.register.success'))
        if (urlParams.has('forumError')) {
            setFailMessage('Forum sign-in could not be completed. Check that your forum account is active and your Minecraft account is verified, then try again.');
        }

        return () => {
            document.body.classList.remove("patriam-login-page");
        }
    }, [setRedirectTo, setSuccessMessage, t])

    useEffect(() => {
        let active = true;
        fetchForumSignIn().then(({data}) => {
            if (active) setForumSignIn(data?.enabled === true);
        });
        return () => { active = false; };
    }, []);

    const redirectAfterLogin = () => {
        if (redirectTo && !redirectTo.startsWith('http') && !redirectTo.startsWith('file') && !redirectTo.startsWith('javascript')) {
            // Normalize the URL so that it can't redirect to different domain.
            try {
                const redirectUrl = new URL(
                    redirectTo.substring(redirectTo.indexOf('/')) + (window.location.hash ? window.location.hash : ''),
                    window.location.protocol + '//' + window.location.host
                );
                if (redirectUrl.pathname.includes("//")) {
                    // Invalid redirect URL, something fishy might be going on, redirect to /
                    navigate('/');
                } else {
                    navigate(
                        redirectUrl.pathname + redirectUrl.search + redirectUrl.hash
                    );
                }
            } catch (e) {
                console.warn(e);
                // Invalid redirect URL, something fishy might be going on, redirect to /
                navigate('/');
            }
        } else {
            navigate('/');
        }
    };

    const login = async (username, password) => {
        if (!username || username.length < 1) {
            return setFailMessage(t('html.register.error.noUsername'));
        }
        if (username.length > 50) {
            return setFailMessage(t('html.register.error.usernameLength') + username.length);
        }
        if (!password || password.length < 1) {
            return setFailMessage(t('html.register.error.noPassword'));
        }

        const {data, error} = await fetchLogin(username, password);

        if (error) {
            if (error.message === 'Request failed with status code 403') {
                // Too many logins, reload browser to show forbidden page
                window.location.reload();
            } else {
                setFailMessage(t('html.login.failed') + (error.data && error.data.error ? error.data.error : error.message));
            }
        } else if (data && data.success) {
            await updateLoginDetails();
            redirectAfterLogin();
            return true;
        } else {
            setFailMessage(t('html.login.failed') + (data ? data.error : t('generic.noData')));
        }
        return false;
    }

    useEffect(() => {
        if (authLoaded && !authRequired || loggedIn) {
            navigate('../');
        }
    }, [authLoaded]);

    if (!authLoaded) {
        return <></>
    }

    return (
        <>
            <div className="patriam-login-shell">
                <a className="patriam-skip-link" href="#sign-in">Skip to sign in</a>
                <header className="patriam-login-header">
                    <a className="patriam-wordmark" href="https://patriamstudios.com/minecraft">
                        <img src={crest} alt="" width="30" height="40"/>
                        <span>Patriam Studios</span>
                    </a>
                    <nav aria-label="Community">
                        <a href="https://patriamstudios.com/minecraft">The server</a>
                        <a href="https://forums.patriam.cc/">Forum</a>
                        <a href="https://forums.patriam.cc/handbook/">Handbook</a>
                    </nav>
                </header>
                <main className="patriam-login-layout">
                    <section className="patriam-login-story" aria-labelledby="patriam-analytics-title">
                        <img className="patriam-login-landscape" src={landscape} alt="" fetchPriority="high"/>
                        <div className="patriam-login-story-copy">
                            <p className="patriam-eyebrow">Edge of the World</p>
                            <h1 id="patriam-analytics-title">Your story,<br/>in numbers.</h1>
                            <p>Explore your time in Patriam.<br/>The places, the people, the history you make.</p>
                        </div>
                        <span className="patriam-landscape-caption">A world shaped by its people</span>
                    </section>
                    <section id="sign-in" className="patriam-login-card" aria-labelledby="patriam-sign-in-title" tabIndex={-1}>
                        <p className="patriam-eyebrow">Player analytics</p>
                        <h2 id="patriam-sign-in-title">Welcome back.</h2>
                    {failMessage && <Alert className='alert-danger'>{failMessage}</Alert>}
                    {successMessage && <Alert className='alert-success'>{successMessage}</Alert>}
                    {forumSignIn && <>
                        <p className="patriam-login-intro">Sign in with the forum account linked to your Minecraft account to view your own statistics.</p>
                        <a className="btn patriam-forum-button w-100" href={`${baseAddress}/auth/forum/start`}>
                            Sign in with your forum account <span aria-hidden="true">↗</span>
                        </a>
                        <p className="patriam-login-help">Need to link your account? <a href="https://forums.patriam.cc/user/connections">Visit Connections</a></p>
                    </>}
                    {forumSignIn ? <details className="patriam-plan-recovery">
                        <summary>Use a Plan account</summary>
                        <LoginForm login={login}/>
                        <ForgotPasswordButton onClick={togglePasswordModal}/>
                    </details> : <>
                        <p className="patriam-login-intro">Sign in with your Plan account.</p>
                        <LoginForm login={login}/>
                        <ForgotPasswordButton onClick={togglePasswordModal}/>
                        <CreateAccountLink/>
                    </>}
                    <ColorChooserButton/>
                    </section>
                </main>
                <footer className="patriam-login-footer">
                    <span>Patriam: Edge of the World</span>
                    <span>Player analytics powered by Plan</span>
                </footer>
            </div>
            <aside>
                <ColorSelectorModal/>
                <ForgotPasswordModal show={forgotPasswordModalOpen} toggle={togglePasswordModal}/>
            </aside>
        </>
    )
};

export default LoginPage
