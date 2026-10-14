import Keycloak from './vendor/keycloak.js';
// The adapter keeps tokens in memory and owns state, nonce and PKCE validation.
let identity;
const message = text => window.dispatchEvent(new CustomEvent('registry-identity-error', {detail:text}));
try {
  const response = await fetch('/api/client-config', {credentials:'omit', cache:'no-store'});
  if (!response.ok) throw new Error();
  const config = await response.json();
  if (config.mode === 'jwt' && config.clientId) {
    const issuer = new URL(config.issuer), split = issuer.pathname.indexOf('/realms/');
    if (issuer.protocol !== 'https:' || split < 0) throw new Error();
    identity = new Keycloak({url: issuer.origin + issuer.pathname.slice(0,split), realm:decodeURIComponent(issuer.pathname.slice(split+8)), clientId:config.clientId});
    const authenticated = await identity.init({pkceMethod:'S256', checkLoginIframe:false, responseMode:'query', redirectUri:location.origin+'/'});
    document.getElementById('login').hidden = true;
    document.getElementById('credential-hint').textContent = 'Tokens stay in page memory. The identity provider manages your password. Use synthetic documents for this demonstration.';
    const button = document.getElementById('identity-login'); button.hidden = false;
    document.getElementById('identity-help').hidden = false;
    button.addEventListener('click', () => {
      if (/^#document=[A-Z0-9-]{3,50}$/.test(location.hash)) sessionStorage.setItem('registry-return-record',location.hash);
      identity.login({redirectUri:location.origin+'/',prompt:'login'}).catch(()=>message('Identity sign-in could not be started.'));
    });
    window.addEventListener('registry-identity-logout', () => {
      const url = identity.createLogoutUrl({redirectUri:location.origin+'/'}); identity.clearToken(); location.assign(url);
    });
    window.addEventListener('registry-identity-clear', () => identity.clearToken());
    if (authenticated) {
      const target=sessionStorage.getItem('registry-return-record'); sessionStorage.removeItem('registry-return-record');
      if (target && /^#document=[A-Z0-9-]{3,50}$/.test(target)) history.replaceState(null,'',location.pathname+target);
      window.dispatchEvent(new CustomEvent('registry-identity-token',{detail:identity.token}));
    }
  }
} catch { message('Identity sign-in is unavailable. Check the staging service and trusted local certificates.'); }
