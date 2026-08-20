/*
 * Filtering the break list, client side.
 *
 * jQuery for the interactive part, which is what a 2011 team used it for. The rows are already on
 * the page - the server rendered them - and this hides and shows them. It does not fetch anything,
 * it does not build the table, and if scripting is off the page is still complete and correct.
 */
$(document).ready(function () {
    function apply() {
        var shown = {};
        $('input.filter:checked').each(function () {
            shown[$(this).val()] = true;
        });
        $('tr.brk').each(function () {
            var row = $(this);
            row.toggle(shown[row.attr('data-classification')] === true);
        });
    }

    $('input.filter').click(apply);
    apply();
});
