package pricemigrationengine.model

import pricemigrationengine.migrations.{
  DigiSubs2025Migration,
  GuardianWeekly2025Migration,
  Membership2025Migration,
  Newspaper2025P1Migration,
  Newspaper2025P3Migration,
  ProductMigration2025N4Migration,
  SupporterPlus2026Migration
}

import java.time.LocalDate

/** The notification lead time (in days, relative to the amendment effective date) within which a cohort item's
  * notification email/letter must be sent, per migration type.
  *
  * Moved out of `NotificationHandler` (which still uses it internally) so that it can also be used by
  * `AmendmentEffectiveDateCalculator`, without `core` depending on the `lambda` handlers module.
  *
  * For general information about the notification period see docs/notification-periods.md.
  *
  * The standard notification period for letter products (where the notification is delivered by email) is -49
  * (included) to -35 (excluded) days. Legally the min is 30 days, but we set 35 days to alert if a subscription is
  * exiting the notification window and needs to be investigated and repaired before the deadline of 30 days.
  *
  * The digital migrations' notification window is from -33 (included) to -31 (excluded).
  */
object NotificationLeadTime {

  def maxLeadTime(cohortSpec: CohortSpec): Int = {
    MigrationType(cohortSpec) match {
      case Test1                  => 35
      case GuardianWeekly2025     => GuardianWeekly2025Migration.maxLeadTime
      case Newspaper2025P1        => Newspaper2025P1Migration.maxLeadTime
      case Newspaper2025P3        => Newspaper2025P3Migration.maxLeadTime
      case ProductMigration2025N4 => ProductMigration2025N4Migration.maxLeadTime
      case Membership2025         => Membership2025Migration.maxLeadTime
      case DigiSubs2025           => DigiSubs2025Migration.maxLeadTime
      case SupporterPlus2026      => SupporterPlus2026Migration.maxLeadTime
    }
  }

  def minLeadTime(cohortSpec: CohortSpec): Int = {
    MigrationType(cohortSpec) match {
      case Test1                  => 33
      case GuardianWeekly2025     => GuardianWeekly2025Migration.minLeadTime
      case Newspaper2025P1        => Newspaper2025P1Migration.minLeadTime
      case Newspaper2025P3        => Newspaper2025P3Migration.minLeadTime
      case ProductMigration2025N4 => ProductMigration2025N4Migration.minLeadTime
      case Membership2025         => Membership2025Migration.minLeadTime
      case DigiSubs2025           => DigiSubs2025Migration.minLeadTime
      case SupporterPlus2026      => SupporterPlus2026Migration.minLeadTime
    }
  }

  def thereIsEnoughNotificationLeadTime(cohortSpec: CohortSpec, today: LocalDate, cohortItem: CohortItem): Boolean = {
    // To help with backward compatibility with existing tests, we apply this condition from 1st Dec 2020.
    if (today.isBefore(LocalDate.of(2020, 12, 1))) {
      true
    } else {
      cohortItem.amendmentEffectiveDate match {
        case Some(sd) => today.plusDays(minLeadTime(cohortSpec)).isBefore(sd)
        case _        => false
      }
    }
  }
}
