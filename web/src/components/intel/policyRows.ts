import type { IntelClass, IntelPolicy, IntelPolicyEntry } from '../../api/types'

/** The login classes, in the order the policy page lists them. */
export const POLICY_CLASSES: readonly IntelClass[] = ['VPN', 'HOSTING', 'PROXY']

/** Whether a route parameter names one of the classes; any other value is not a policy page. */
export function isPolicyClass(value: unknown): value is IntelClass {
  return POLICY_CLASSES.some((classification) => classification === value)
}

/** The policy of one class as stored. A class without a stored row is FLAG, as the backend reports it. */
export function policyOf(policies: IntelPolicyEntry[], classification: IntelClass): IntelPolicy {
  return (policies.find((entry) => entry.classification === classification)?.policy ?? 'FLAG') as IntelPolicy
}

/** The policy an administrator can choose: DENY is offered to an administrator only. */
export function choosablePolicies(administrator: boolean): IntelPolicy[] {
  return administrator ? ['ALLOW', 'FLAG', 'DENY'] : ['ALLOW', 'FLAG']
}
