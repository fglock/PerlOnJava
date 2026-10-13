use strict;
use warnings;
use Test::More;
use IO::Handle;
use Errno qw(ENOENT);

subtest 'append-open scalar output stays undefined until written' => sub {
    my $output;
    {
        local *STDERR;
        open STDERR, '>>', \$output or die "open scalar STDERR: $!";
    }
    ok(!defined $output, 'closing an unused append scalar handle preserves undef');
};

subtest 'truncate failures return undef and set errno' => sub {
    my $missing = "filehandle-truncate-missing-$$";
    my $result = truncate($missing, 0);
    ok(!defined $result, 'truncate of a missing path returns undef');
    is(0 + $!, ENOENT, 'truncate of a missing path sets ENOENT');

    open my $read_only, '<', __FILE__ or die "open read-only test file: $!";
    $result = truncate($read_only, 0);
    ok(!defined $result, 'truncate through a read-only filehandle returns undef');
    ok(0 + $! > 0, 'truncate through a read-only filehandle sets errno');
    close $read_only or die "close read-only test file: $!";
};

subtest 'bareword pipe handles support IO::Handle methods' => sub {
    pipe(FROM_CHILD, TO_PARENT) or die "pipe: $!";
    is(TO_PARENT->autoflush(1), 0, 'autoflush dispatches on a bareword pipe handle');
    print TO_PARENT "pipe-method-dispatch\n" or die "pipe write: $!";
    close TO_PARENT or die "close pipe writer: $!";
    is(<FROM_CHILD>, "pipe-method-dispatch\n", 'pipe data reaches its reader');
    close FROM_CHILD or die "close pipe reader: $!";
};

subtest 'filehandle input can write to scalar-reference output' => sub {
    my $template = "[% group %]\n";
    open my $input, '<', \$template or die "open scalar input: $!";
    my $line = <$input>;
    close $input or die "close scalar input: $!";

    my $output;
    open my $output_fh, '>', \$output or die "open scalar output: $!";
    $output_fh->print($line =~ /\[% group %\]/ ? "hoge foo bar\n" : "bad template\n");
    close $output_fh or die "close scalar output: $!";
    is($output, "hoge foo bar\n", 'filehandle input output is stored in the scalar reference');
};

done_testing;
