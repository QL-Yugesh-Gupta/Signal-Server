/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.backup;

import io.grpc.Status;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.signal.libsignal.zkgroup.GenericServerSecretParams;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.backups.BackupAuthCredentialRequest;
import org.signal.libsignal.zkgroup.backups.BackupAuthCredentialResponse;
import org.signal.libsignal.zkgroup.backups.BackupLevel;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.signal.libsignal.zkgroup.receipts.ReceiptSerial;
import org.signal.libsignal.zkgroup.receipts.ServerZkReceiptOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.controllers.RateLimitExceededException;
import org.whispersystems.textsecuregcm.experiment.ExperimentEnrollmentManager;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.RedeemedReceiptsManager;
import org.whispersystems.textsecuregcm.util.Util;
import javax.annotation.Nullable;

/**
 * Issues ZK backup auth credentials for authenticated accounts
 * <p>
 * Authenticated callers can create ZK credentials that contain a blinded backup-id, so that they can later use that
 * backup id without the verifier learning that the id is associated with this account.
 * <p>
 * First use {@link #commitBackupId} to provide a blinded backup-id. This is stored in durable storage. Then the caller
 * can use {@link #getBackupAuthCredentials} to retrieve credentials that can subsequently be used to make anonymously
 * authenticated requests against their backup-id.
 */
public class BackupAuthManager {

  private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);


  final static Duration MAX_REDEMPTION_DURATION = Duration.ofDays(7);
  final static String BACKUP_EXPERIMENT_NAME = "backup";
  final static String BACKUP_MEDIA_EXPERIMENT_NAME = "backupMedia";

  private final ExperimentEnrollmentManager experimentEnrollmentManager;
  private final GenericServerSecretParams serverSecretParams;
  private final ServerZkReceiptOperations serverZkReceiptOperations;
  private final RedeemedReceiptsManager redeemedReceiptsManager;
  private final Clock clock;
  private final RateLimiters rateLimiters;
  private final AccountsManager accountsManager;

  public BackupAuthManager(
      final ExperimentEnrollmentManager experimentEnrollmentManager,
      final RateLimiters rateLimiters,
      final AccountsManager accountsManager,
      final ServerZkReceiptOperations serverZkReceiptOperations,
      final RedeemedReceiptsManager redeemedReceiptsManager,
      final GenericServerSecretParams serverSecretParams,
      final Clock clock) {
    this.experimentEnrollmentManager = experimentEnrollmentManager;
    this.rateLimiters = rateLimiters;
    this.accountsManager = accountsManager;
    this.serverZkReceiptOperations = serverZkReceiptOperations;
    this.redeemedReceiptsManager = redeemedReceiptsManager;
    this.serverSecretParams = serverSecretParams;
    this.clock = clock;
  }

  /**
   * Store a credential request containing a blinded backup-id for future use.
   *
   * @param account                     The account using the backup-id
   * @param backupAuthCredentialRequest A request containing the blinded backup-id
   * @return A future that completes when the credentialRequest has been stored
   * @throws RateLimitExceededException If too many backup-ids have been committed
   */
  public CompletableFuture<Void> commitBackupId(final Account account,
      final BackupAuthCredentialRequest backupAuthCredentialRequest) {
    if (configuredBackupLevel(account).isEmpty()) {
      throw Status.PERMISSION_DENIED.withDescription("Backups not allowed on account").asRuntimeException();
    }

    byte[] serializedRequest = backupAuthCredentialRequest.serialize();
    byte[] existingRequest = account.getBackupCredentialRequest();
    if (existingRequest != null && MessageDigest.isEqual(serializedRequest, existingRequest)) {
      // No need to update or enforce rate limits, this is the credential that the user has already
      // committed to.
      return CompletableFuture.completedFuture(null);
    }

    return rateLimiters.forDescriptor(RateLimiters.For.SET_BACKUP_ID)
        .validateAsync(account.getUuid())
        .thenCompose(ignored -> this.accountsManager
            .updateAsync(account, acc -> acc.setBackupCredentialRequest(serializedRequest))
            .thenRun(Util.NOOP))
        .toCompletableFuture();
  }

  public record Credential(BackupAuthCredentialResponse credential, Instant redemptionTime) {}

  /**
   * Create a credential for every day between redemptionStart and redemptionEnd
   * <p>
   * This uses a {@link BackupAuthCredentialRequest} previous stored via {@link this#commitBackupId} to generate the
   * credentials.
   * <p>
   * If the account has a BackupVoucher allowing access to paid backups, credentials with a redemptionTime before the
   * voucher's expiration will include paid backup access. If the BackupVoucher exists but is already expired, this
   * method will also remove the expired voucher from the account.
   *
   * @param account         The account to create the credentials for
   * @param redemptionStart The day (must be truncated to a day boundary) the first credential should be valid
   * @param redemptionEnd   The day (must be truncated to a day boundary) the last credential should be valid
   * @return Credentials and the day on which they may be redeemed
   */
  public CompletableFuture<List<Credential>> getBackupAuthCredentials(
      final Account account,
      final Instant redemptionStart,
      final Instant redemptionEnd) {

    // If the account has an expired payment, clear it before continuing
    if (hasExpiredVoucher(account)) {
      return accountsManager.updateAsync(account, a -> {
        // Re-check in case we raced with an update
        if (hasExpiredVoucher(a)) {
          a.setBackupVoucher(null);
        }
      }).thenCompose(updated -> getBackupAuthCredentials(updated, redemptionStart, redemptionEnd));
    }

    // If this account isn't allowed some level of backup access via configuration, don't continue
    final BackupLevel configuredBackupLevel = configuredBackupLevel(account).orElseThrow(() ->
        Status.PERMISSION_DENIED.withDescription("Backups not allowed on account").asRuntimeException());

    final Instant startOfDay = clock.instant().truncatedTo(ChronoUnit.DAYS);
    if (redemptionStart.isAfter(redemptionEnd) ||
        redemptionStart.isBefore(startOfDay) ||
        redemptionEnd.isAfter(startOfDay.plus(MAX_REDEMPTION_DURATION)) ||
        !redemptionStart.equals(redemptionStart.truncatedTo(ChronoUnit.DAYS)) ||
        !redemptionEnd.equals(redemptionEnd.truncatedTo(ChronoUnit.DAYS))) {

      throw Status.INVALID_ARGUMENT.withDescription("invalid redemption window").asRuntimeException();
    }

    // fetch the blinded backup-id the account should have previously committed to
    final byte[] committedBytes = account.getBackupCredentialRequest();
    if (committedBytes == null) {
      throw Status.NOT_FOUND.withDescription("No blinded backup-id has been added to the account").asRuntimeException();
    }

    try {
      // create a credential for every day in the requested period
      final BackupAuthCredentialRequest credentialReq = new BackupAuthCredentialRequest(committedBytes);
      return CompletableFuture.completedFuture(Stream
          .iterate(redemptionStart, curr -> curr.plus(Duration.ofDays(1)))
          .takeWhile(redemptionTime -> !redemptionTime.isAfter(redemptionEnd))
          .map(redemptionTime -> {
            // Check if the account has a voucher that's good for a certain receiptLevel at redemption time, otherwise
            // use the default receipt level
            final BackupLevel backupLevel = storedBackupLevel(account, redemptionTime).orElse(configuredBackupLevel);
            return new Credential(
                credentialReq.issueCredential(redemptionTime, backupLevel, serverSecretParams),
                redemptionTime);
          })
          .toList());
    } catch (InvalidInputException e) {
      throw Status.INTERNAL
          .withDescription("Could not deserialize stored request credential")
          .withCause(e)
          .asRuntimeException();
    }
  }

  /**
   * Redeem a receipt to enable paid backups on the account.
   *
   * @param account                       The account to enable backups on
   * @param receiptCredentialPresentation A ZK receipt presentation proving payment
   * @return A future that completes successfully when the account has been updated
   */
  public CompletableFuture<Void> redeemReceipt(
      final Account account,
      final ReceiptCredentialPresentation receiptCredentialPresentation) {
    try {
      serverZkReceiptOperations.verifyReceiptCredentialPresentation(receiptCredentialPresentation);
    } catch (VerificationFailedException e) {
      throw Status.INVALID_ARGUMENT
          .withDescription("receipt credential presentation verification failed")
          .asRuntimeException();
    }
    final ReceiptSerial receiptSerial = receiptCredentialPresentation.getReceiptSerial();
    final Instant receiptExpiration = Instant.ofEpochSecond(receiptCredentialPresentation.getReceiptExpirationTime());
    if (clock.instant().isAfter(receiptExpiration)) {
      throw Status.INVALID_ARGUMENT.withDescription("receipt is already expired").asRuntimeException();
    }

    final long receiptLevel = receiptCredentialPresentation.getReceiptLevel();

    if (BackupLevelUtil.fromReceiptLevel(receiptLevel) != BackupLevel.MEDIA) {
      throw Status.INVALID_ARGUMENT
          .withDescription("server does not recognize the requested receipt level")
          .asRuntimeException();
    }

    return redeemedReceiptsManager
        .put(receiptSerial, receiptExpiration.getEpochSecond(), receiptLevel, account.getUuid())
        .thenCompose(receiptAllowed -> {
          if (!receiptAllowed) {
            throw Status.INVALID_ARGUMENT
                .withDescription("receipt serial is already redeemed")
                .asRuntimeException();
          }
          return accountsManager.updateAsync(account, a -> {
            final Account.BackupVoucher newPayment = new Account.BackupVoucher(receiptLevel, receiptExpiration);
            final Account.BackupVoucher existingPayment = a.getBackupVoucher();
            account.setBackupVoucher(merge(existingPayment, newPayment));
          });
        })
        .thenRun(Util.NOOP);
  }

  private static Account.BackupVoucher merge(@Nullable final Account.BackupVoucher prev,
      final Account.BackupVoucher next) {
    if (prev == null) {
      return next;
    }

    if (next.receiptLevel() != prev.receiptLevel()) {
      return next;
    }

    // If the new payment has the same receipt level as the old, select the further out of the two expiration times
    if (prev.expiration().isAfter(next.expiration())) {
      // This should be fairly rare, either a client reused an old receipt or we reduced the validity period
      logger.warn(
          "Redeemed receipt with an expiration at {} when we've previously had a redemption with a later expiration {}",
          next.expiration(), prev.expiration());
      return prev;
    }
    return next;
  }

  private boolean hasExpiredVoucher(final Account account) {
    return account.getBackupVoucher() != null && clock.instant().isAfter(account.getBackupVoucher().expiration());
  }

  /**
   * Get the receipt level stored in the {@link Account.BackupVoucher} on the account if it's present and not expired.
   *
   * @param account        The account to check
   * @param redemptionTime The time to check against the expiration time
   * @return The receipt level on the backup voucher, or empty if the account does not have one or it is expired
   */
  private Optional<BackupLevel> storedBackupLevel(final Account account, final Instant redemptionTime) {
    return Optional.ofNullable(account.getBackupVoucher())
        .filter(backupVoucher -> !redemptionTime.isAfter(backupVoucher.expiration()))
        .map(Account.BackupVoucher::receiptLevel)
        .map(BackupLevelUtil::fromReceiptLevel);
  }

  /**
   * Get the backup receipt level that should be used by default for this account determined via configuration.
   *
   * @param account the account to check
   * @return If present, the default receipt level that should be used for the account if the account does not have a
   * BackupVoucher. Empty if the account should never have backup access
   */
  private Optional<BackupLevel> configuredBackupLevel(final Account account) {
    if (inExperiment(BACKUP_MEDIA_EXPERIMENT_NAME, account)) {
      return Optional.of(BackupLevel.MEDIA);
    }
    if (inExperiment(BACKUP_EXPERIMENT_NAME, account)) {
      return Optional.of(BackupLevel.MESSAGES);
    }
    return Optional.empty();
  }

  private boolean inExperiment(final String experimentName, final Account account) {
    return this.experimentEnrollmentManager.isEnrolled(account.getUuid(), experimentName);
  }
}
