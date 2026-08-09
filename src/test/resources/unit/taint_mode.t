#!perl -T
use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Fcntl qw(O_RDONLY O_WRONLY);
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

my $tainted_counter = 1 + (0 + $empty_taint);
my $old_counter = $tainted_counter++;
ok(tainted($old_counter), 'post-increment result preserves input taint');
ok(tainted($tainted_counter), 'post-increment lvalue remains tainted');
$old_counter = $tainted_counter--;
ok(tainted($old_counter), 'post-decrement result preserves input taint');
ok(tainted($tainted_counter), 'post-decrement lvalue remains tainted');

$text =~ /^(.*)$/;
ok(!tainted($1), 'regex capture untaints validated input');

my $tainted_pattern = "(abc)$empty_taint";
'abc' =~ /$tainted_pattern/;
ok(tainted($1), 'a tainted regex pattern taints its capture');
ok(tainted(qr/$tainted_pattern/), 'qr preserves pattern taint');

{
    use re 'taint';
    $text =~ /^(.*)$/;
    ok(tainted($1), q{use re 'taint' preserves input taint in captures});

    my $capture = sub { $_[0] =~ /^(.*)$/; $1 };
    ok(tainted($capture->($text)), 'capture returned from a sub preserves taint');
    ok(!tainted($capture->('clean')), 'capture taint does not stick to later matches');
}

my $sub_source = "abcd$empty_taint";
my $sub_count = $sub_source =~ s/(.+)/xyz/;
ok(tainted($sub_source), 'substitution preserves source taint on the target');
ok(!tainted($sub_count), 'single substitution count stays clean');
ok(!tainted($1), 'ordinary substitution capture untaints input');

$sub_source = "abcd$empty_taint";
$sub_count = $sub_source =~ s/(.)/x/g;
ok(tainted($sub_count), 'global substitution count depends on tainted input');

my $sub_pattern = "(.+)$empty_taint";
my $sub_target = 'abcd';
$sub_count = $sub_target =~ s/$sub_pattern/xyz/;
ok(tainted($sub_target), 'tainted substitution pattern taints the target');
ok(!tainted($sub_count), 'single substitution count ignores pattern taint');
ok(tainted($1), 'tainted substitution pattern taints captures');

my $sub_replacement = "xyz$empty_taint";
$sub_target = 'abcd';
$sub_count = $sub_target =~ s/(.+)/$sub_replacement/;
ok(tainted($sub_target), 'tainted replacement taints the substitution target');
ok(!tainted($sub_count), 'substitution count ignores replacement taint');

$sub_target = 'abcd';
my $sub_copy = $sub_target =~ s/(.+)/$sub_replacement/r;
ok(!tainted($sub_target), 'non-destructive substitution leaves its source clean');
ok(tainted($sub_copy), 'non-destructive substitution preserves replacement taint');

{
    package TaintStringify;
    use overload '""' => sub { $_[0]->[0] };
    sub new { bless [$_[1]], $_[0] }
}

my $tainted_object = TaintStringify->new("object$empty_taint");
ok(tainted("$tainted_object"), 'stringification overload preserves result taint');
ok(tainted("prefix$tainted_object"), 'mixed interpolation preserves overload result taint');
$sub_target = 'abcd';
$sub_target =~ s/(.+)/prefix$tainted_object/;
ok(tainted($sub_target), 'interpolated overloaded replacement preserves taint');
$sub_target = 'abcd';
$sub_copy = $sub_target =~ s/(.+)/$tainted_object/r;
ok(tainted($sub_copy), 'whole overloaded replacement preserves taint');

{
    use re 'taint';
    $sub_source = "abcd$empty_taint";
    $sub_source =~ s/(.+)/xyz/;
    ok(tainted($1), q{use re 'taint' taints substitution captures from tainted input});
}

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

    local $ENV{PATH} = '.';
    my $path_ok = eval { qx{/bin/echo path-check}; 1 };
    ok(!$path_ok, 'process launch rejects a relative PATH directory');
    like($@, qr/Insecure directory in \$ENV\{PATH\}/,
        'relative PATH reports the Perl security error');

    local $ENV{PATH} = '/usr/bin';
    local $ENV{TERM} = 'dumb';
    my $command_output = qx{/bin/echo external-output};
    ok(tainted($command_output), 'command output enters Perl as tainted data');

    local $ENV{TERM} = "unsafe=$empty_taint";
    my $term_ok = eval { qx{/bin/echo term-check}; 1 };
    ok(!$term_ok, 'process launch rejects tainted TERM metacharacters');
    like($@, qr/Insecure \$ENV\{TERM\}/,
        'tainted TERM reports the Perl security error');
}

ok(tainted($0), 'the program name is tainted under taint mode');

{
    open my $ioctl_fh, '<', $^X or die $!;
    my $ioctl_ok = eval { ioctl $ioctl_fh, 0 + $empty_taint, "x$empty_taint"; 1 };
    ok(!$ioctl_ok, 'ioctl rejects tainted control arguments');
    like($@, qr/^Insecure dependency in ioctl while running with -T switch/,
        'ioctl reports the Perl security error');
    close $ioctl_fh;
}

{
    open my $source, '<', $^X or die $!;
    local $/;
    my $contents = <$source>;
    my $eof = <$source>;
    ok(tainted($contents), 'file input enters Perl as tainted data');
    ok(tainted($eof), 'undef returned at file EOF retains input provenance');
    close $source;
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

my $read_open_ok = eval { open my $fh, '<', $tainted_path; 1 };
ok($read_open_ok, 'three-argument open permits a tainted read path');
my $write_open_ok = eval { open my $fh, '>', $tainted_path; 1 };
ok(!$write_open_ok, 'three-argument open rejects a tainted write path');
like($@, qr/^Insecure dependency in open while running with -T switch/,
    'write open reports the Perl security error');

my $sysread_open_ok = eval { sysopen my $fh, $tainted_path, O_RDONLY; 1 };
ok($sysread_open_ok, 'sysopen permits a tainted read-only path');
my $missing_sysopen = sysopen my $missing_fh, $tainted_path, O_RDONLY;
ok(!defined($missing_sysopen), 'failed read-only sysopen returns undef');
my $syswrite_open_ok = eval { sysopen my $fh, $tainted_path, O_WRONLY; 1 };
ok(!$syswrite_open_ok, 'sysopen rejects a tainted write path');
like($@, qr/^Insecure dependency in sysopen while running with -T switch/,
    'sysopen reports the Perl security error');

{
    no warnings 'numeric';
    my $tainted_zero = 0 + $empty_taint;
    ok(tainted(O_WRONLY | $tainted_zero), 'bitwise flags preserve taint');
    my $tainted_read_mode = O_RDONLY | $tainted_zero;
    my $tainted_read_ok = eval {
        sysopen my $fh, '/tmp/perlonjava-taint-no-such', $tainted_read_mode;
        1;
    };
    ok($tainted_read_ok, 'sysopen permits tainted read-only flags');
    my $tainted_write_mode = O_WRONLY | $tainted_zero;
    my $tainted_write_ok = eval {
        sysopen my $fh, '/tmp/perlonjava-taint-no-such', $tainted_write_mode;
        1;
    };
    ok(!$tainted_write_ok, 'sysopen rejects tainted write flags');
    like($@, qr/^Insecure dependency in sysopen while running with -T switch/,
        'tainted sysopen flags report the Perl security error');
    for my $case (
        [ truncate => sub { truncate '/tmp/perlonjava-taint-no-such', $tainted_zero } ],
        [ utime    => sub { utime $tainted_zero, $tainted_zero, '/tmp/perlonjava-taint-no-such' } ],
        [ chown    => sub { chown -1, -1, $tainted_path } ],
    ) {
        my ($operation, $code) = @$case;
        my $ok = eval { $code->(); 1 };
        ok(!$ok, "$operation rejects tainted arguments");
        like($@, qr/^Insecure dependency in $operation while running with -T switch/,
            "$operation reports the Perl security error");
    }
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
