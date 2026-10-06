use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

SKIP: {
    skip 'child-process timeout launcher is unavailable on Windows', 3
        if $^O eq 'MSWin32';

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} <<'CHILD';
use strict;
use warnings;
use Scalar::Util qw(weaken);

{
    package GlobalWeakOwner::Destroy;
    sub cleanup { my $self = shift; return $self->{value} }
    sub DESTROY { my $self = shift; $self->cleanup }
}

{
    package GlobalNoWeakOwner::Destroy;
    sub cleanup { my $self = shift; return $self->{value} }
    sub DESTROY { my $self = shift; $self->cleanup }
}

our $object = bless { value => 1 }, 'GlobalWeakOwner::Destroy';
our $weak = $object;
weaken($weak);
our $existing_alias = $object;
our $unweak_object = bless { value => 2 }, 'GlobalNoWeakOwner::Destroy';
CHILD
    close($script_fh) or die "close child script: $!";

    my ($output_fh, $output_name) = tempfile();
    open(my $saved_stderr, '>&', \*STDERR) or die "save stderr: $!";
    open(STDERR, '>&', $output_fh) or die "redirect stderr: $!";
    my $runner = $^X eq 'jperl' ? './jperl' : $^X;
    my $status = system('timeout', '60', $runner, $script_name);
    open(STDERR, '>&', $saved_stderr) or die "restore stderr: $!";
    close($saved_stderr);

    seek($output_fh, 0, 0);
    my $output = do { local $/; <$output_fh> };
    close($output_fh);
    unlink($script_name);
    unlink($output_name);

    is($status, 0, 'global destruction completes cleanly');
    unlike($output,
        qr/DESTROY created new reference to dead object 'GlobalWeakOwner::Destroy'/,
        'existing global owners and temporary destructor references are not reported as resurrection');
    unlike($output,
        qr/DESTROY created new reference to dead object 'GlobalNoWeakOwner::Destroy'/,
        'unweak temporary destructor references are not reported as resurrection');
}

done_testing;
