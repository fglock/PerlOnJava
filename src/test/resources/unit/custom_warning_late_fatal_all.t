use strict;
use warnings FATAL => 'all';
use Test::More;

my $loaded = eval q{
    package LateFatalCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('late fatal category payload');
    }

    1;
};
ok($loaded, 'registered a category after FATAL all was compiled');

my @warnings;
my $ok;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $ok = eval { LateFatalCategory::emit_warning(); 1 };
}
ok(!$ok, 'late registered category inherits FATAL all');
like($@, qr/^late fatal category payload/,
    'late fatal exception retains its payload');
is_deeply(\@warnings, [], 'fatal category is not emitted as an ordinary warning');

my @suppressed;
my $suppressed = eval q{
    no warnings 'LateFatalCategory';
    local $SIG{__WARN__} = sub { push @suppressed, @_ };
    LateFatalCategory::emit_warning();
    1;
};
ok($suppressed, 'explicit late-category disable avoids the fatal warning');
is_deeply(\@suppressed, [], 'explicit late-category disable remains silent');

done_testing;
