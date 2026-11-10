import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface UserRow { id: string; email: string; displayName: string; roles: string[]; status: string }

export function Users() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-users"], queryFn: () => api<UserRow[]>("/admin/users") });
  const set = useMutation({
    mutationFn: ({ id, admin }: { id: string; admin: boolean }) => api(`/admin/users/${id}/admin`, { method: "POST", body: { admin } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin-users"] }),
  });
  return (
    <div>
      <PageTitle>Users</PageTitle>
      {(q.error || set.error) && <ErrorNote error={q.error ?? set.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      <table className="w-full text-sm">
        <thead className="text-left text-muted"><tr><th className="py-2 font-medium">Name</th><th className="font-medium">Email</th><th className="font-medium">Role</th><th /></tr></thead>
        <tbody>
          {q.data?.map((u) => {
            const admin = u.roles.includes("ADMIN");
            return (
              <tr key={u.id} className="border-t border-rule">
                <td className="py-2">{u.displayName}</td>
                <td className="text-muted">{u.email}</td>
                <td>{admin ? "Admin" : "User"}</td>
                <td className="text-right">
                  {u.id !== user?.id && (
                    <button className="text-signal hover:underline" disabled={set.isPending}
                      onClick={() => { if (confirm(admin ? `Remove admin rights from ${u.displayName}?` : `Make ${u.displayName} an admin?`)) set.mutate({ id: u.id, admin: !admin }); }}>
                      {admin ? "Remove admin" : "Make admin"}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="text-sm text-muted mt-4">Admins can author and publish problems, run AI ingestion and manage fests. Role changes apply at the person's next token refresh.</p>
    </div>
  );
}