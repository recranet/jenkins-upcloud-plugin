Behaviour.specify("[data-type='upcloud-provision']", "upcloud-provision", -99, function (button) {
  button.addEventListener("click", function () {
    const form = document.getElementById(button.dataset.form);
    form.querySelector("[name='template']").value = button.dataset.template;
    buildFormTree(form);
    form.submit();
  });
});
