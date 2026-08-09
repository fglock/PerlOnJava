#!perl -T
use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Scalar::Util qw(tainted);
use Test::More;

my $empty_taint = substr($^X, 0, 0);
my $text = "abc$empty_taint";

ok(${^TAINT}, '-T enables ${^TAINT}');
ok(tainted($^X), '$^X is tainted');
ok(tainted($ENV{PATH}), '%ENV values are tainted');
ok(tainted($empty_taint), 'substr preserves source taint');
ok(tainted($text), 'concatenation propagates taint');

my $copy = $text;
ok(tainted($copy), 'assignment propagates taint');
{
    no warnings 'numeric';
    ok(tainted(1 + $empty_taint), 'addition propagates taint');
    ok(tainted(2 * (1 + $empty_taint)), 'multiplication propagates taint');
}
ok(tainted(length($text)), 'length propagates taint');
ok(!tainted(length('abc')), 'taint propagation does not contaminate cached clean scalars');
ok(tainted(uc($text)), 'case conversion propagates taint');
ok(tainted(ord($text)), 'ord propagates taint');

$text =~ /^(.*)$/;
ok(!tainted($1), 'regex capture untaints validated input');

sub perlsec_tainted {
    return !eval { no warnings; join('', @_), kill 0; 1 };
}

ok(perlsec_tainted($text), 'legacy perlsec join/kill probe detects taint');
ok(!perlsec_tainted('clean'), 'legacy perlsec join/kill probe accepts clean data');

my $eval_ok = eval { eval $text; 1 };
ok(!$eval_ok, 'tainted eval STRING is rejected');
like($@, qr/^Insecure dependency in eval while running with -T switch/,
    'tainted eval reports the Perl security error');

{
    local @ENV{qw(PATH IFS CDPATH ENV BASH_ENV)};
    $ENV{PATH} = '/usr/bin';
    delete @ENV{qw(IFS CDPATH ENV BASH_ENV)};

    my $system_ok = eval { system("/bin/echo$empty_taint", 'unused'); 1 };
    ok(!$system_ok, 'system rejects a tainted command');
    like($@, qr/^Insecure dependency in system while running with -T switch/,
        'system reports the Perl security error');

    my $qx_ok = eval { qx{/bin/echo$empty_taint unused}; 1 };
    ok(!$qx_ok, 'qx rejects a tainted command');
    like($@, qr/^Insecure dependency in `` while running with -T switch/,
        'qx reports the Perl security error');
}

my $tainted_path = "/tmp/perlonjava-taint-no-such-$$" . $empty_taint;
my @file_operations = (
    [ unlink  => sub { unlink $tainted_path } ],
    [ mkdir   => sub { mkdir $tainted_path } ],
    [ rmdir   => sub { rmdir $tainted_path } ],
    [ chdir   => sub { chdir $tainted_path } ],
    [ rename  => sub { rename $tainted_path, "$tainted_path-new" } ],
    [ link    => sub { link $tainted_path, "$tainted_path-link" } ],
    [ symlink => sub { symlink $tainted_path, "$tainted_path-symlink" } ],
    [ chmod   => sub { chmod 0600, $tainted_path } ],
    [ require => sub { require $tainted_path } ],
    [ do      => sub { do $tainted_path } ],
);

for my $case (@file_operations) {
    my ($operation, $code) = @$case;
    my $ok = eval { $code->(); 1 };
    ok(!$ok, "$operation rejects a tainted path");
    like($@,
        qr/^Insecure dependency in $operation while running with -T switch/,
        "$operation reports the Perl security error");
}

for (qw(x y z)) {
    my $outer_topic = $_;
    my $letter = "$_$empty_taint";
    my $result = do {
        no warnings 'deprecated';
        given ($_) {
            when ('x') { $letter }
            when ('y') { goto leave_given }
            default { $letter }
            leave_given: $letter
        }
    };
    is($result, $letter, "given preserves the result for $outer_topic");
    ok(tainted($result), "given preserves taint for $outer_topic");
    is($_, $outer_topic, "given restores the foreach topic for $outer_topic");
}

my @split = split /!/, "left!right$empty_taint";
ok(tainted($split[0]) && tainted($split[1]), 'split propagates input taint');

{
    no warnings 'numeric';
    ok(tainted(~("abc$empty_taint")), 'bitwise complement propagates taint');
}
ok(tainted(crypt('secret', "aa$empty_taint")), 'crypt propagates salt taint');
ok(tainted(vec("A$empty_taint", 0, 8)), 'vec propagates source taint');
ok(tainted(pack('a*', "packed$empty_taint")), 'pack propagates value taint');

ok(tainted(sprintf('%s', "formatted$empty_taint", 'clean')),
    'sprintf propagates used argument taint');
{
    no warnings 'numeric';
    my $tainted_zero = 0 + $empty_taint;
    ok(!tainted(sprintf('%s', 'clean', $tainted_zero)),
        'sprintf ignores an unused tainted numeric argument');
}

my $tainted_format = "%s$empty_taint";
my $sprintf_ok = eval { sprintf($tainted_format, 'value'); 1 };
ok(!$sprintf_ok, 'sprintf rejects a tainted format');
like($@, qr/^Insecure dependency in sprintf while running with -T switch/,
    'sprintf reports the Perl security error');

my $printf_ok = eval { printf($tainted_format, 'value'); 1 };
ok(!$printf_ok, 'printf rejects a tainted format');
like($@, qr/^Insecure dependency in printf while running with -T switch/,
    'printf reports the Perl security error');

done_testing;
