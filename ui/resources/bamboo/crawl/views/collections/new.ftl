[@page title="New Collection"]

<h3>New Collection</h3>

<div class="well">
    <form method="post" class="form-horizontal">
        <input name="csrfToken" value="${csrfToken}" type="hidden">

        <fieldset>
            [#include "_form.ftl"]

            <div class="form-group">
                <div class="col-lg-10 col-lg-offset-2">
                    <button type="submit" class="btn btn-primary">Create Collection</button>
                    <a href="collections" class="btn btn-default">Cancel</a>
                </div>
            </div>
        </fieldset>
    </form>
</div>

[/@page]