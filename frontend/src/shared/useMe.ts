import { useQuery } from '@tanstack/react-query'
import { api } from '@/shared/api/client'

/**
 * VYB-0765 AC2: the one place nav and the command palette both read "can this caller
 * reach Administration" from — a single query, cached, rather than each place
 * re-deriving it.
 */
export function useMe() {
  return useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: 60_000 })
}
