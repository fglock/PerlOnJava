#!perl -T
use strict;
use warnings;
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

done_testing;
