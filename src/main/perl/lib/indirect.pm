package indirect;

use strict;
use warnings;

our $VERSION = '0.39';
our $GLOBAL_POLICY = '';
our $GLOBAL_HOOK;

my $POLICY_HINT = 'indirect/policy';
my $HOOK_HINT = 'indirect/hook';

# `use indirect` disables the diagnostic for the current lexical scope.
sub import {
    shift;
    $^H{$POLICY_HINT} = 'off';
    delete $^H{$HOOK_HINT};
}

# The parser reads these lexical hints while it recognizes indirect method
# calls. Keeping the policy in %^H gives `use indirect` normal block scoping.
sub unimport {
    shift;
    my ($global, $fatal, $hook);
    while (@_) {
        my $arg = shift;
        if ($arg eq 'hook') {
            die "The 'fatal' and 'hook' options are mutually exclusive\n" if $fatal;
            $hook = shift;
            die "The 'hook' option requires a code reference\n"
                unless ref($hook) eq 'CODE';
        }
        elsif ($arg =~ /^:?fatal$/i) {
            die "The 'fatal' and 'hook' options are mutually exclusive\n"
                if defined $hook;
            $fatal = 1;
        }
        elsif ($arg =~ /^:?global$/i) {
            $global = 1;
        }
        else {
            die "Unknown indirect option '$arg'\n";
        }
    }

    my $policy = defined($hook) ? 'hook' : $fatal ? 'fatal' : 'warn';
    if ($global) {
        $GLOBAL_POLICY = $policy;
        $GLOBAL_HOOK = $hook;
        delete $^H{$POLICY_HINT};
        delete $^H{$HOOK_HINT};
    }
    else {
        $^H{$POLICY_HINT} = $policy;
        if (defined $hook) {
            $^H{$HOOK_HINT} = $hook;
        }
        else {
            delete $^H{$HOOK_HINT};
        }
    }
}

1;

__END__

=head1 NAME

indirect - Lexically warn about indirect method call syntax

=head1 DESCRIPTION

This bundled compatibility pragma controls parser diagnostics for indirect
method calls. C<no indirect> warns by default; C<no indirect 'fatal'> makes
them compile-time errors; C<no indirect hook =E<gt> \&callback> invokes a
callback with the invocant, method name, file, and line. C<use indirect>
disables the policy in its lexical scope. The C<global> option installs a
policy for subsequently compiled code.

=cut
