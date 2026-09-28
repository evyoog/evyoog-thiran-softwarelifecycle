import { describe, it, expect } from 'vitest'
import type { Application, Capability } from '@/shared/api/client'
import { resolveApplicationFromCapability } from './CapabilityPicker'

function application(over: Partial<Application> = {}): Application {
  return { id: 'app1', productId: 'prod1', name: 'App', archived: false, ...over }
}

function capability(over: Partial<Capability> = {}): Capability {
  return { id: 'cap1', applicationId: 'app1', name: 'Cap', archived: false, ...over }
}

describe('VYB-0801 — capability picker resolves product/application from a capability id alone', () => {
  it('VYB0801_AC1_findsTheApplicationAndProductOwningTheCapability', () => {
    // Bulk edit's selection agreed on one capability, but the grid was not scoped to any
    // one application ("All requirements" — scope is null), so no initialApplicationId
    // was ever available to resolve from. The only clue is the capability id itself, so
    // every application in the portfolio is probed for which one lists it.
    const applications = [
      application({ id: 'app-crm', productId: 'prod-sales' }),
      application({ id: 'app-billing', productId: 'prod-finance' }),
    ]
    const capsByApplication = [
      [capability({ id: 'cap-other', applicationId: 'app-crm' })],
      [capability({ id: 'cap-invoice', applicationId: 'app-billing' })],
    ]
    expect(resolveApplicationFromCapability(applications, capsByApplication, 'cap-invoice')).toEqual({
      productId: 'prod-finance',
      applicationId: 'app-billing',
    })
  })

  it('VYB0801_AC1_unresolvedWhileNothingHasLoadedYet', () => {
    // Capability lookups fire one query per application and arrive one at a time — while
    // every one of them is still undefined, there is nothing to conclude from yet.
    const applications = [application({ id: 'app-crm' })]
    expect(resolveApplicationFromCapability(applications, [undefined], 'cap-invoice')).toBeNull()
  })

  it('VYB0801_AC1_unresolvedWhenNothingInThePortfolioOwnsIt', () => {
    // A stale capability id (its application or capability archived out from under it
    // since) must not be mistaken for a match — the picker should keep showing "—" rather
    // than a false positive.
    const applications = [application({ id: 'app-crm' })]
    const capsByApplication = [[capability({ id: 'cap-other', applicationId: 'app-crm' })]]
    expect(resolveApplicationFromCapability(applications, capsByApplication, 'cap-gone')).toBeNull()
  })

  it('VYB0801_AC1_someApplicationsStillLoadingDoesNotStopTheOthersMatching', () => {
    // Lookups resolve independently; one still-pending application (undefined) must not
    // block a match already available from another that has already resolved.
    const applications = [
      application({ id: 'app-crm', productId: 'prod-sales' }),
      application({ id: 'app-billing', productId: 'prod-finance' }),
    ]
    const capsByApplication = [undefined, [capability({ id: 'cap-invoice', applicationId: 'app-billing' })]]
    expect(resolveApplicationFromCapability(applications, capsByApplication, 'cap-invoice')).toEqual({
      productId: 'prod-finance',
      applicationId: 'app-billing',
    })
  })
})
