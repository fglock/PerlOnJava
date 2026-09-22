use Test::More;

my $safe = sub {
    my $value = 5;
    sub () { $value };
}->();
is(&$safe, 5, 'an unmodified lexical constant CV returns its lexical value');

my $optimized = sub {
    my $value = 5;
    sub () { 0; $value };
}->();
is(&$optimized, 5, 'an optimized statement before a lexical constant is inlinable');

{
    package Local::MooStyleConstantClosure;
    BEGIN {
        my $phase_code = q[${^GLOBAL_PHASE} eq 'DESTRUCT'];
        *in_global_destruction_code = sub () { $phase_code };
        eval "sub in_global_destruction () { $phase_code }; 1" or die $@;
    }
}
ok(defined &Local::MooStyleConstantClosure::in_global_destruction,
    'a Moo-style BEGIN constant closure permits its lexical initializer');

my $mutation_generator = sub {
    my $value = 5;
    my $constant = sub () { $value };
    $value = 7;
    $constant;
};
my $mutation_error = do {
    local $@;
    eval {
        my $constant = &$mutation_generator;
        1;
    };
    $@;
};
like($mutation_error,
    qr/Constants from lexical variables potentially modified elsewhere are no longer permitted/,
    'a later lexical mutation rejects the historical constant-CV optimization');

{
    use feature 'refaliasing';
    no warnings 'experimental::refaliasing';
    my $aliased = sub {
        my $alias = \(my $value = 1);
        my $closure = sub () { $value };
        $$alias += 7;
        return $closure;
    }->();
    is(&$aliased, 8, 'a refaliased lexical remains a live closure value');
}

done_testing;
