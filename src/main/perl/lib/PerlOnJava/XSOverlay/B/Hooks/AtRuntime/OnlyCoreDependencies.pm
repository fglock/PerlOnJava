package B::Hooks::AtRuntime::OnlyCoreDependencies;

# Parser-independent fallback primitives for the distribution's filter-based
# implementation. The CPAN module supplies at_runtime/after_runtime itself.
sub count_BEGINs { 1 }
sub compiling_string_eval { 0 }
sub remaining_text { undef }
my %stable_hooks;
my $next_hook_id = 0;

sub lex_stuff {
    require Filter::Util::Call;
    my $id = ++$next_hook_id;
    Filter::Util::Call::filter_add(sub {
        $_ = 'BEGIN{' . __PACKAGE__ . "::capture_stable($id)}"
            . __PACKAGE__ . "::run_stable($id);BEGIN{" . __PACKAGE__
            . "::clear(1)}";
        Filter::Util::Call::filter_del();
        return 1;
    });
    return;
}

sub capture_stable {
    my ($id) = @_;
    no strict 'refs';
    $stable_hooks{$id} = [ @{ __PACKAGE__ . '::hooks' } ];
    no warnings 'redefine';
    *USE_FILTER = sub () { 1 };
    return;
}

sub run_stable {
    my ($id) = @_;
    # The injected call may live inside a loop or reusable subroutine.  Keep
    # its callback list for the lifetime of that compiled code, matching the
    # CV references embedded in perl5's optree.
    my $callbacks = $stable_hooks{$id} || [];
    return run(@$callbacks);
}

sub run {
    for my $callback (@_) {
        if (ref($callback) eq 'REF') {
            ${$callback}->();
        }
        else {
            $callback->();
        }
    }
    return;
}

1;
