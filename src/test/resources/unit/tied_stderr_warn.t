use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempfile);

package WarnTie::Handle;

sub TIEHANDLE {
    my $class = shift;
    return bless \$_[0], $class;
}

sub PRINT {
    my $self = shift;
    $$self .= join '', @_;
}

package main;

my $err = '';
tie *STDERR, 'WarnTie::Handle', $err;
warn "warning\n";
print STDERR "printed\n";
{
    no warnings 'untie';
    untie *STDERR;
}

is($err, "warning\nprinted\n", 'warn writes through tied STDERR');

subtest 'warn with localized unopened STDERR' => sub {
    plan tests => 2;

    my ($tmp_fh, $tmp_path) = tempfile();
    close $tmp_fh;

    open my $saved_stderr, '>&', \*STDERR or die "save STDERR: $!";
    open STDERR, '>', $tmp_path or die "redirect STDERR: $!";

    my $ok = eval {
        local *STDERR;
        my $empty = '';
        my $sum = 1 + $empty;
        1;
    };
    my $eval_error = $@;

    open STDERR, '>&', $saved_stderr or die "restore STDERR: $!";
    close $saved_stderr;

    ok($ok, "warning under local *STDERR does not die: $eval_error");

    open my $read_fh, '<', $tmp_path or die "read captured STDERR: $!";
    my $captured = do { local $/; <$read_fh> };
    close $read_fh;
    unlink $tmp_path;

    like($captured, qr/Argument "" isn't numeric/, 'warning still reaches real STDERR');
};
