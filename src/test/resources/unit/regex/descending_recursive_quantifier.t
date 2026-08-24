use strict;
use warnings;
use Test::More tests => 13;

my (@warnings, $result, $error);
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval q{ "foo" =~ /((?1)){8,0}/ };
    $error = $@;
}
is($error, '', 'descending recursive interval remains nonfatal');
ok(!$result, 'descending recursive interval is an impossible match');
is(scalar @warnings, 1, 'descending recursive interval warns once');
like($warnings[0],
    qr/^Quantifier \{n,m\} with n > m can't match in regex/,
    'descending recursive interval retains the regexp diagnostic');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval q{ no warnings 'regexp'; "foo" =~ /((?1)){8,0}/ };
    $error = $@;
}
is($error, '', 'disabled regexp warnings do not expose recursion diagnostics');
ok(!$result, 'disabled descending recursive interval remains impossible');
is_deeply(\@warnings, [], 'regexp category suppresses the interval warning');

$result = eval q{
    use warnings FATAL => 'regexp';
    "foo" =~ /((?1)){8,0}/;
};
ok(!defined $result, 'fatal regexp policy rejects the descending interval warning');
like($@, qr/^Quantifier \{n,m\} with n > m can't match in regex/,
    'fatal regexp policy retains the interval diagnostic');

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval q{ no warnings 'regexp'; "foo" =~ /(foo){1,0}|(?1)/ };
    $error = $@;
}
is($error, '', 'an impossible branch does not invalidate reachable recursion');
ok($result, 'reachable recursive alternative retains Perl match behavior');
is_deeply(\@warnings, [], 'disabled interval branch remains warning-free');

$result = eval q{ "foo" =~ /((?1)){1,8}/ };
like($@, qr/^Infinite recursion in regex/,
    'reachable never-ending recursion remains fatal');
