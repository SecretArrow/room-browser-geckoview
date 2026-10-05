package com.roombrowser.di

import android.content.Context
import com.roombrowser.data.db.AppDatabase
import com.roombrowser.data.filters.FilterListLoader
import com.roombrowser.data.repo.AppStateRepository
import com.roombrowser.data.repo.BrowserRepository
import com.roombrowser.data.repo.ProfileRepositoryImpl
import com.roombrowser.domain.engine.FilterEngine
import com.roombrowser.domain.engine.IpConflictDetector
import com.roombrowser.domain.profile.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Simple manual dependency graph (no framework needed for this scope).
 * Works in BOTH the main process and the ':browser' process.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Scope for the few writes that must deliberately OUTLIVE the screen that
     * started them — a provider saved from the editor the user closes in the
     * same breath (AgentProviderEditorActivity), and nothing that can wait.
     *
     * It exists so such work has an OWNER. The alternative that was there
     * before — GlobalScope — is attached to nothing: no shutdown path, no
     * cancellation, nothing any test or any reader can point at to say what is
     * still running. Lives as long as the process (one per process, like the
     * rest of the graph) and is never cancelled: the process ending IS its
     * cancellation.
     *
     * Dispatchers.Main.immediate like every state holder in this app — a launch
     * from a click handler starts its body SYNCHRONOUSLY inside that handler,
     * so what the user typed is read before anything can change it, while the
     * suspending persistence primitives switch to their own dispatchers
     * internally. SupervisorJob: one failed write must not take the scope down
     * with it.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val appState: AppStateRepository by lazy { AppStateRepository(database.appStateDao()) }

    val profileRepo: ProfileRepositoryImpl by lazy { ProfileRepositoryImpl(database) }

    /** Per-profile theme snapshots + the user's custom-theme gallery. */
    val themeRepo: com.roombrowser.data.repo.ThemeRepository by lazy {
        com.roombrowser.data.repo.ThemeRepository(database)
    }

    val browserRepo: BrowserRepository by lazy { BrowserRepository(database) }

    val agentRepo: com.roombrowser.data.repo.AgentRepository by lazy {
        com.roombrowser.data.repo.AgentRepository(database)
    }

    /** Scheduled AI tasks: definitions plus their run bookkeeping. */
    val aiTaskRepo: com.roombrowser.data.repo.AiTaskRepository by lazy {
        com.roombrowser.data.repo.AiTaskRepository(database.aiTaskDao())
    }

    /**
     * The WORKER's execution seam: it records a due occurrence and says why it
     * could not run it (see DeferredAiTaskRunner). The run itself happens in
     * ':browser', through [aiTaskDelivery].
     */
    val aiTaskRunner: com.roombrowser.agent.AiTaskRunner by lazy {
        com.roombrowser.agent.AiTaskRunners.forContext(appContext)
    }

    /**
     * Executes the occurrences the worker recorded. Only ':browser' starts it:
     * that is the process that owns the engine runtime, and a second one over
     * the same profile data is the thing this design exists to avoid.
     */
    val aiTaskDelivery: com.roombrowser.agent.AiTaskDelivery by lazy {
        com.roombrowser.agent.AiTaskDelivery.forBrowserProcess(this, appContext)
    }

    /**
     * Password manager: per-profile credential vault. The DAO is plain Room
     * (multi-instance invalidation already keeps both processes in sync);
     * the cryptor is the AndroidKeyStore-backed VaultCrypto. NOTE: the vault
     * starts LOCKED in each process — the UI layer owns the biometric gate
     * and calls CredentialRepository.unlock() once per session.
     */
    val credentialRepo: com.roombrowser.data.repo.CredentialRepository by lazy {
        com.roombrowser.data.repo.CredentialRepository(
            database.credentialDao(),
            com.roombrowser.security.VaultCrypto
        )
    }

    /**
     * Multi-chain wallet: per-profile wallet data. The DAOs are plain Room;
     * the cryptor is the AndroidKeyStore-backed WalletKeyCrypto — a SEPARATE
     * key from the password vault's (alias roomwallet-<safeSuffix>), so the
     * two vaults never share key material. Plaintext key material crosses
     * this boundary only via WalletRepository's create/import/reveal calls;
     * reveal is the UI layer's to gate behind biometrics.
     */
    val walletRepo: com.roombrowser.data.repo.WalletRepository by lazy {
        com.roombrowser.data.repo.WalletRepository(
            database.walletDao(),
            database.walletAccountDao(),
            database.walletNetworkDao(),
            database.dappPermissionDao(),
            database.walletActivityDao(),
            com.roombrowser.security.WalletKeyCrypto
        )
    }

    val profileManager: ProfileManager by lazy { ProfileManager(profileRepo) }

    /**
     * Wallet engine: the session/state holder for the bound profile's wallet
     * (accounts, balances, networks, dApp request queue). Main-thread
     * confined like the rest of the graph; both wallet surfaces (WalletActivity
     * and the browsing engine's dApp bridge) live in the ':browser' process,
     * so they share ONE engine instance — unlocking in the dashboard unlocks
     * in-page dApp signing for the same session, and vice versa.
     */
    val walletEngine: com.roombrowser.browser.wallet.WalletEngine by lazy {
        com.roombrowser.browser.wallet.WalletEngine(walletRepo)
    }

    val filterEngine: FilterEngine by lazy { FilterListLoader.load(appContext) }

    val ipConflictDetector: IpConflictDetector by lazy { IpConflictDetector() }
}
