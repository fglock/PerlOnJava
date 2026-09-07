use strict;
use warnings;
use Test::More;

{
    package Local::FallbackGuard;

    sub DESTROY { ${$_[0]}->() }
}

sub make_guard (&) {
    bless \(my $callback = shift), 'Local::FallbackGuard';
}

{
    package Local::FallbackRegistry;

    our $DEBUG = 0;

    sub new { bless { next => 'a', entries => [] }, shift }

    sub register {
        my ($self, @args) = @_;
        my $debuginfo = caller;
        if ($DEBUG > 0) {
            my ($package, $file, $line) = caller;
            $debuginfo = "$file:$line ($package::)";
        }

        my $generation = $self->{next}++;
        my @callbacks;
        while (@args) {
            my ($event, $callback) = (shift @args, shift @args);
            my ($priority, $registered) = (0, undef);
            if (ref $callback) {
                $registered = $callback;
            } else {
                $priority = $callback;
                $registered = shift @args;
            }
            push @callbacks, $registered;
            push @{$self->{entries}}, "$registered|$generation";
        }

        defined wantarray
            ? \(my $guard = main::make_guard {
                if ($self) {
                    $self->remove($_, $generation) for @callbacks;
                }
            })
            : ();
    }

    sub remove {
        my ($self, $callback, $generation) = @_;
        push @main::removed_generations, $generation;
        @{$self->{entries}} = grep { $_ ne "$callback|$generation" }
            @{$self->{entries}};
    }
}

my $registry = Local::FallbackRegistry->new;
my $callback = sub { };
our @removed_generations;
my $guard = $registry->register(event => $callback);
$guard = $registry->register(event => $callback);

is scalar @{$registry->{entries}}, 1,
    'a returned scalar-reference guard survives assignment after interpreter fallback';
is_deeply \@removed_generations, ['a'],
    'replacing the caller guard releases only the prior registration';

undef $guard;
is scalar @{$registry->{entries}}, 0,
    'the final guard is released when its caller-owned reference is dropped';
is_deeply \@removed_generations, ['a', 'b'],
    'dropping the final guard releases its registration';

done_testing;
