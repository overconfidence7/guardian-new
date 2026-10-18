/*
 * Mirror of the built-in list in core/AppCatalog.kt, used only to explain things in the
 * dashboard ("Chase is already watched as a bank app"). The phone is the authority.
 */
import type { AppCategory } from './model';

const cat = (category: AppCategory, ...pkgs: string[]) => pkgs.map((p) => [p, category] as const);

export const BUILT_IN_CATALOG: ReadonlyMap<string, AppCategory> = new Map<string, AppCategory>([
  ...cat(
    'REMOTE_ACCESS',
    'com.anydesk.anydeskandroid',
    'com.teamviewer.quicksupport.market',
    'com.teamviewer.teamviewer.market.mobile',
    'com.teamviewer.host.market',
    'com.carriez.flutter_hbb',
    'com.splashtop.sos',
    'com.splashtop.remote.pad.v2',
    'com.splashtop.streamer.csrs',
    'com.zoho.assist.agent',
    'com.zoho.assist',
    'com.screenconnect.androidclient',
    'com.logmein.rescuemobile',
    'com.sand.airdroid',
    'com.sand.airmirror',
    'com.realvnc.viewer.android',
    'com.google.chromeremotedesktop',
    'com.microsoft.rdc.androidx',
  ),
  ...cat(
    'PAYMENT',
    'com.paypal.android.p2pmobile',
    'com.venmo',
    'com.squareup.cash',
    'com.zellepay.zelle',
    'com.westernunion.android.mtapp',
    'com.moneygram.moneygram',
    'com.remitly.androidapp',
    'com.xoom.android.app',
    'com.worldremit.android',
    'com.google.android.apps.walletnfcrel',
    'com.samsung.android.spay',
  ),
  ...cat(
    'GIFT_CARD',
    'com.raise.raise',
    'com.cardcash.app',
    'com.amazon.mShop.android.shopping',
    'com.walmart.android',
    'com.target.ui',
    'com.bestbuy.android',
    'com.cvs.launchers.cvs',
    'com.walgreens.android',
    'com.ebay.mobile',
  ),
  ...cat(
    'CRYPTO',
    'com.coinbase.android',
    'com.coinbase.wallet',
    'co.mona.android',
    'com.binance.dev',
    'com.binance.us',
    'com.kraken.trade',
    'com.robinhood.android',
    'com.wallet.crypto.trustapp',
    'io.metamask',
    'exodusmovement.exodus',
    'com.coinme.coinme',
    'com.bitpay.wallet',
  ),
  ...cat(
    'BANK',
    'com.chase.sig.android',
    'com.infonow.bofa',
    'com.wf.wellsfargomobile',
    'com.citi.citimobile',
    'com.konylabs.capitalone',
    'com.usbank.mobilebanking',
    'com.pnc.ecommerce.mobile',
    'com.tdbank',
    'com.truist.mobile',
    'com.navyfederal.android',
    'com.usaa.mobile.android.usaa',
    'com.discoverfinancial.mobile',
    'com.ally.MobileBanking',
    'com.schwab.mobile',
    'com.fidelity.android',
    'com.americanexpress.android.acctsvcs.us',
  ),
]);

export const CATEGORY_LABELS: Record<AppCategory, { label: string; plural: string; description: string }> = {
  BANK: { label: 'Bank', plural: 'Bank apps', description: 'Chase, Bank of America, Wells Fargo, credit unions' },
  PAYMENT: { label: 'Payment', plural: 'Payment apps', description: 'Zelle, PayPal, Venmo, Cash App, Western Union' },
  GIFT_CARD: { label: 'Gift cards', plural: 'Gift-card stores', description: 'Amazon, Walmart, Target, CVS, Walgreens' },
  CRYPTO: { label: 'Crypto', plural: 'Crypto apps', description: 'Coinbase, Crypto.com, Binance, Bitcoin ATMs' },
  REMOTE_ACCESS: { label: 'Remote control', plural: 'Remote-control apps', description: 'AnyDesk, TeamViewer, RustDesk' },
};

export function builtInCategory(packageName: string): AppCategory | undefined {
  return BUILT_IN_CATALOG.get(packageName);
}

export function isBuiltInRemoteAccess(packageName: string): boolean {
  return BUILT_IN_CATALOG.get(packageName) === 'REMOTE_ACCESS';
}
