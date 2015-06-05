[@page title="Task Manager"]

<h3>Task Manager</h3>

<table class="table">
    <tr><th>Task</th><th>Status</th><th></th></tr>
    [#list tasks as task]
        <tr>
            <td>${task.name}</td>
            <td>${task.status}</td>
            <td>
                [#if task.isEnabled()]
                    <form method="post" action="tasks/${task.id}/disable">
                        <input type="hidden" name="csrfToken" value="${csrfToken}">
                        <button>Disable</button>
                    </form>
                [#else]
                    <form method="post" action="tasks/${task.id}/enable">
                        <input type="hidden" name="csrfToken" value="${csrfToken}">
                        <button>Enable</button>
                    </form>
                [/#if]
            </td>
        </tr>
    [/#list]
</table>
[/@page]
