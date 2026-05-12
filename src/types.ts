import { EmitterSubscription } from "react-native";

export enum EncryptionMode {
  None = "None",
  Fast = "Fast",
  Strong = "Strong",
}

export enum EllipticCurve {
  Secp256k1 = "secp256k1",
  Secp256r1 = "secp256r1",
  Ed25519 = "ed25519",
  Ed25519Slip0010 = "ed25519_slip0010",
  Bip0340 = "bip0340",
  Bls12381G2 = "bls12381_G2",
  Bls12381G2Aug = "bls12381_G2_AUG",
  Bls12381G2Pop = "bls12381_G2_POP",
}

/**
 * Multi-curve, multi-path derivation map for `defaultDerivationPaths`.
 * Keys are {@link EllipticCurve} raw values; values are arrays of raw derivation
 * path strings. Enables HD-wallet auto-derivation on curves other than secp256k1.
 *
 * @example
 *   // Hedera (SLIP-0010 ED25519 on BIP-44 coin-type 3030):
 *   {
 *     "ed25519_slip0010": [
 *       "m/44'/3030'/0'/0'/0'",
 *       "m/44'/3030'/0'/0'/1'"
 *     ]
 *   }
 */
export type DefaultDerivationPathsMap = Partial<Record<`${EllipticCurve}`, string[]>>;

export enum LinkedTerminalStatus {
  Current = "current",
  Other = "other",
  None = "none",
}

export enum AttestationStatus {
  Failed = "failed",
  Warning = "warning",
  Skipped = "skipped",
  VerifiedOffline = "verifiedOffline",
  Verified = "verified",
}

export interface ExtendedPublicKey {
  publicKey: string;
  chainCode: string;
}

export interface CardWallet {
  /**
   *  Wallet's public key.
   */
  publicKey: string;
  /**
   * Optional chain code for BIP32 derivation.
   */
  chainCode?: string;
  /**
   *  Elliptic curve used for all wallet key operations.
   */
  curve: EllipticCurve;
  /**
   *  Wallet's settings
   */
  settings: Settings;
  /**
   * Total number of signed hashes returned by the wallet since its creation
   * COS 1.16+
   */
  totalSignedHashes?: number;
  /**
   * Remaining number of `Sign` operations before the wallet will stop signing any data.
   * Note: This counter were deprecated for cards with COS 4.0 and higher
   */
  remainingSignatures?: number;
  /**
   * Index of the wallet in the card storage
   */
  index: number;
  /**
   * Does this wallet has a backup
   */
  hasBackup: number;
  /**
   * Does this wallet has a backup
   */
  derivedKeys: ExtendedPublicKey[];
}

export interface Issuer {
  /**
   * Name of the issuer.
   */
  name: string;
  /**
   * Public key that is used by the card issuer to sign IssuerData field.
   */
  publicKey: string;
}

export interface Settings {
  /**
   * Delay in milliseconds before executing a command that affects any sensitive data or wallets on the card
   */
  securityDelay: number;

  /**
   * Maximum number of wallets that can be created for this card
   */
  maxWalletsCount: number;

  /**
   * Is allowed to change access code
   */
  isSettingAccessCodeAllowed: boolean;

  /**
   * Is  allowed to change passcode
   */
  isSettingPasscodeAllowed: boolean;

  /**
   * Is allowed to remove access code
   */
  isResettingUserCodesAllowed: boolean;

  /**
   * Is LinkedTerminal feature enabled
   */
  isLinkedTerminalEnabled: boolean;

  /**
   * All  encryption modes supported by the card
   */
  supportedEncryptionModes: Array<EncryptionMode>;
  /**
   * Is allowed to write files
   */
  isFilesAllowed: boolean;
  /**
   * Is allowed to use hd wallet
   */
  isHDWalletAllowed: boolean;
  /**
   * Is allowed to delete wallet. COS before v4
   */
  isPermanentWallet: boolean;
  /**
   * Is overwriting issuer extra data restricted
   */
  isOverwritingIssuerExtraDataRestricted: boolean;
  /**
   * Is Issuer data protected against replay
   */
  isIssuerDataProtectedAgainstReplay: boolean;
  /**
   * Is select blockchain allowed
   */
  isSelectBlockchainAllowed: boolean;
}

export interface Manufacturer {
  /**
   * Card manufacturer name.
   */
  name: string;
  /**
   * Timestamp of manufacturing.
   */
  manufactureDate: Date;
  /**
   * Signature of CardId with manufacturer’s private key. COS 1.21+
   */
  signature?: string;
}

export interface FirmwareVersion {
  stringValue: string;
  major: number;
  minor: number;
  patch: number;
  type: number;
}

export interface Attestation {
  cardKeyAttestation: AttestationStatus;
  walletKeysAttestation: AttestationStatus;
  firmwareAttestation: AttestationStatus;
  cardUniquenessAttestation: AttestationStatus;
}

export interface Card {
  /**
   * Unique Tangem card ID number.
   */
  cardId: string;

  /**
   * Tangem internal manufacturing batch ID.
   */
  batchId: string;

  /**
   * Public key that is used to authenticate the card against manufacturer’s database.
   * It is generated one time during card manufacturing.
   */
  cardPublicKey: string;

  /**
   * Version of Tangem COS.
   */
  firmwareVersion: FirmwareVersion;

  /**
   * Information about manufacturer.
   */
  manufacturer: Manufacturer;

  /**
   * Information about issuer
   */
  issuer: Issuer;

  /**
   * Card setting, that were set during the personalization process
   */
  settings: Settings;

  /**
   * When this value is `current`, it means that the application is linked to the card,
   * and COS will not enforce security delay if `SignCommand` will be called
   * with `TlvTag.TerminalTransactionSignature` parameter containing a correct signature of raw data
   * to be signed made with `TlvTag.TerminalPublicKey`.
   * */
  linkedTerminalStatus: LinkedTerminalStatus;

  /**
   * PIN1 (aka AccessCode) is set.
   */
  isAccessCodeSet: boolean;

  /**
   * PIN2 (aka Passcode) is set.
   */
  isPasscodeSet: boolean;

  /**
   * Array of ellipctic curves, supported by this card. Only wallets with these curves can be created.
   */
  supportedCurves: Array<EllipticCurve>;

  /**
   * Wallets, created on the card, that can be used for signature
   */
  wallets: Array<CardWallet>;

  /**
   * Card's attestation report
   */
  attestation: Attestation;
}

export type CreateWalletResponse = {
  /**
   * CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * Created wallet
   */
  wallet: CardWallet;
};

export type SignResponse = {
  /**
   * CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * signature Signed hashes (array of resulting signatures)
   */
  signatures: string[];
  /**
   * Total number of signed single hashes returned by the card in sign command responses.
   */
  totalSignedHashes?: number;
};

export type PurgeWalletResponse = {
  /**
   * CID, Unique Tangem card ID number.
   */
  cardId: string;
};

export type SetUserCodesResponse = {
  /**
   * CID, Unique Tangem card ID number.
   */
  cardId: string;
};

/* Methods options ==================================================================== */
export type NFCStatusResponse = {
  enabled: boolean;
  support: boolean;
};

/**
 * Event's listeneres
 */
export type Events = "NFCStateChange";
export type EventCallback = {
  enabled: boolean;
};

export type InitialMessage = {
  header?: string;
  body?: string;
};

export interface OptionsCommon {
  /**
   * A custom description that shows at the beginning of the NFC session. If nil, default message will be used
   */
  initialMessage?: InitialMessage;
}

export interface OptionsCreateWallet extends OptionsCommon {
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * A custom description that shows at the beginning of the NFC session. If nil, default message will be used
   */
  curve?: "ed25519" | "secp256k1" | "secp256r1";
}

export interface OptionsPurgeWallet extends OptionsCommon {
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * A custom description that shows at the beginning of the NFC session. If nil, default message will be used
   */
  walletPublicKey: string;
}

export interface OptionsSign extends OptionsCommon {
  /**
   * Array of transaction hashes. It can be from one or up to ten hashes of the same length.
   */
  hashes: [string];
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * A custom description that shows at the beginning of the NFC session. If nil, default message will be used
   */
  walletPublicKey: string;
  /**
   * Derivation path of the wallet. Optional. COS v. 4.28 and higher,
   */
  derivationPath?: string;
  /**
   * @deprecated 'hdPath' has been deprecated, please use 'derivationPath' instead!
   */
  hdPath?: string;
}

export interface OptionsSetAccessCode extends OptionsCommon {
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * Access code to set. If nil, the user will be prompted to enter code before operation
   */
  accessCode?: string;
}

export interface OptionsSetPasscode extends OptionsCommon {
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
  /**
   * Passcode to set. If nil, the user will be prompted to enter code before operation
   */
  passcode?: string;
}

export interface OptionsResetUserCodes extends OptionsCommon {
  /**
   * cardId: CID, Unique Tangem card ID number.
   */
  cardId: string;
}

export interface OptionsStartSession extends OptionsCommon {
  /**
   * ScanTask or scanCard method in TangemSdk class will use this mode to attest the card
   */
  attestationMode?: "full" | "nomral" | "offline";
  /**
   * On cards with the HD-wallet feature enabled, derives keys automatically during
   * `scanCard` and `createWallet`. Repeated items are ignored. All derived keys are
   * stored in `Card.Wallet.derivedKeys`.
   *
   * Three input shapes are accepted by the native bridge:
   *
   *   1. `string`
   *      Single derivation path; applied to the `secp256k1` curve. This preserves
   *      the original v3.1.0 behaviour for existing XRP-style callers.
   *
   *   2. `string[]`
   *      Multiple derivation paths; all applied to the `secp256k1` curve.
   *
   *   3. {@link DefaultDerivationPathsMap}
   *      Multi-curve, multi-path mapping. Required for chains that use a curve
   *      other than `secp256k1`, e.g. Hedera (SLIP-0010 ED25519).
   *
   * @example
   *   // Hedera — 16 accounts on BIP-44 coin-type 3030 (SLIP-0010 ED25519):
   *   await RNTangemSdk.startSession({
   *     defaultDerivationPaths: {
   *       "ed25519_slip0010": Array.from({length: 16}, (_, i) => `m/44'/3030'/0'/0'/${i}'`)
   *     }
   *   });
   */
  defaultDerivationPaths?: string | string[] | DefaultDerivationPathsMap;
}

export type RNTangemSdkModule = {
  startSession(options: OptionsStartSession): Promise<void>;
  stopSession(): Promise<void>;
  scanCard(options?: OptionsCommon): Promise<Card>;
  createWallet(options: OptionsCreateWallet): Promise<CreateWalletResponse>;
  purgeWallet(options: OptionsPurgeWallet): Promise<PurgeWalletResponse>;
  sign(options: OptionsSign): Promise<SignResponse>;
  setAccessCode(options: OptionsSetAccessCode): Promise<SetUserCodesResponse>;
  setPasscode(options: OptionsSetPasscode): Promise<SetUserCodesResponse>;
  resetUserCodes(options: OptionsResetUserCodes): Promise<SetUserCodesResponse>;
  getNFCStatus(): Promise<NFCStatusResponse>;
  addListener(
    eventName: Events,
    handler: (state: EventCallback) => void
  ): EmitterSubscription | undefined;
};
