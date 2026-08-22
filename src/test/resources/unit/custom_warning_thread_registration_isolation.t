use strict;
use warnings;
use threads;
use Test::More;

sub register_and_emit {
    my ($package) = @_;
    my $loaded = eval qq{
        package $package;
        use warnings::register;
        sub emit_warning { warnings::warnif('$package payload') }
        1;
    };
    return [0, $@] unless $loaded;

    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        no strict 'refs';
        &{"${package}::emit_warning"}();
    }
    return [scalar @warnings, $warnings[0] // ''];
}

my @threads = map {
    my $package = "ThreadOnlyCategory$_";
    threads->create(\&register_and_emit, $package);
} 1 .. 4;

for my $index (1 .. 4) {
    my $result = $threads[$index - 1]->join;
    is($result->[0], 1, "thread $index registered and emitted its private category");
    like($result->[1], qr/^ThreadOnlyCategory$index payload/,
        "thread $index retained its private payload");
}

my $parent_knows_child_category = eval q{
    no warnings 'ThreadOnlyCategory1';
    1;
};
ok(!$parent_knows_child_category,
    'child warning registration does not mutate the parent registry');
like($@, qr/Unknown warnings category/, 'parent reports the child category as unknown');

done_testing;
