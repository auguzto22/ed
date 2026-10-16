-- Excluir conta pelo app (exigido pelo Google Play).
-- Chamada por AccountManager.deleteAccount via POST /rest/v1/rpc/delete_user.
create or replace function public.delete_user()
returns void
language sql
security definer
set search_path = ''
as $$
  delete from auth.users where id = auth.uid();
$$;

revoke all on function public.delete_user() from public, anon;
grant execute on function public.delete_user() to authenticated;
