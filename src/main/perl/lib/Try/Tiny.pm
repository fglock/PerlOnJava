package Try::Tiny;
use strict;
use warnings;

# Bundled Try::Tiny implementation for PerlOnJava
# Based on Try::Tiny 0.32, simplified for compatibility

our $VERSION = '0.32';

use Exporter 'import';
our @EXPORT = our @EXPORT_OK = qw(try catch finally);

use Carp;

# Try to load Sub::Util or Sub::Name for naming blocks
BEGIN {
    my $su = eval { require Sub::Util; defined &Sub::Util::set_subname };
    my $sn = !$su && eval { require Sub::Name; Sub::Name->VERSION(0.08) };
    
    *_subname = $su ? \&Sub::Util::set_subname
              : $sn ? \&Sub::Name::subname
              : sub { $_[1] };
    *_HAS_SUBNAME = ($su || $sn) ? sub(){1} : sub(){0};
}

# Blessed wrapper for catch blocks
sub catch (&;@) {
    my ($block, @rest) = @_;
    # Detect bare catch() in void context
    croak 'Useless bare catch()' unless wantarray;
    # Name the block if we can
    _subname(caller() . '::catch {...} ' => $block) if _HAS_SUBNAME;
    return (bless(\$block, 'Try::Tiny::Catch'), @rest);
}

# Blessed wrapper for finally blocks
sub finally (&;@) {
    my ($block, @rest) = @_;
    # Detect bare finally() in void context
    croak 'Useless bare finally()' unless wantarray;
    # Name the block if we can
    _subname(caller() . '::finally {...} ' => $block) if _HAS_SUBNAME;
    return (bless(\$block, 'Try::Tiny::Finally'), @rest);
}

sub try (&;@) {
    my ($try, @code_refs) = @_;
    
    # Name the try block if we can
    _subname(caller() . '::try {...} ' => $try) if _HAS_SUBNAME;
    
    # Save calling context
    my $wantarray = wantarray;
    
    # Parse catch and finally blocks
    my ($catch, @finally);
    for my $code_ref (@code_refs) {
        if (ref($code_ref) eq 'Try::Tiny::Catch') {
            if ($catch) {
                require Carp;
                Carp::croak('A try() may not be followed by multiple catch() blocks');
            }
            $catch = ${$code_ref};
        }
        elsif (ref($code_ref) eq 'Try::Tiny::Finally') {
            push @finally, ${$code_ref};
        }
        else {
            require Carp;
            Carp::croak(
                'try() encountered an unexpected argument ('
                . (defined $code_ref ? $code_ref : 'undef')
                . ') - perhaps a missing semi-colon before or'
            );
        }
    }
    
    # Name the try block if we can
    _subname(caller() . '::try {...} ' => $try) if _HAS_SUBNAME;
    
    # Save $@ to restore later
    my $prev_error = $@;
    
    # Execute try block
    my ($failed, $error, @ret);
    {
        local $@;
        $failed = not eval {
            $@ = $prev_error;  # Restore $@ inside eval for code that checks it
            if ($wantarray) {
                @ret = $try->();
            }
            elsif (defined $wantarray) {
                $ret[0] = $try->();
            }
            else {
                $try->();
            }
            1;
        };
        $error = $@;
    }
    
    # Restore $@
    $@ = $prev_error;
    
    # Execute catch block if we failed
    my $catch_error;
    my $catch_failed;
    if ($failed && $catch) {
        # Set up $_ and @_ for catch block
        local $_ = $error;
        my @catch_args = ($error);
        
        # Preserve $@ in catch block too, wrap in eval to catch exceptions
        {
            local $@;
            $catch_failed = not eval {
                $@ = $prev_error;
                if ($wantarray) {
                    @ret = $catch->(@catch_args);
                }
                elsif (defined $wantarray) {
                    $ret[0] = $catch->(@catch_args);
                }
                else {
                    $catch->(@catch_args);
                }
                1;
            };
            $catch_error = $@ if $catch_failed;
        }
        $@ = $prev_error;
    }
    
    # Execute finally blocks (always, in void context)
    my @finally_errors;
    for my $finally_block (@finally) {
        local $@;
        my @finally_args = $failed ? ($error) : ();
        eval {
            $finally_block->(@finally_args);
            1;
        } or do {
            # Collect errors from finally blocks, warn about them
            push @finally_errors, $@;
        };
    }
    
    # Warn about any finally block errors (match original Try::Tiny format)
    for my $finally_error (@finally_errors) {
        warn
            "Execution of finally() block CODE("
            . sprintf("0x%x", 0 + \$finally_error)
            . ") resulted in an exception, which "
            . '*CAN NOT BE PROPAGATED* due to fundamental limitations of Perl. '
            . 'Your program will continue as if this event never took place. '
            . "Original exception text follows:\n\n"
            . (defined $finally_error ? $finally_error : '$@ left undefined...')
            . "\n";
    }
    
    # Restore $@ one more time
    $@ = $prev_error;
    
    # Re-throw if catch block died (after finally blocks have run)
    if ($catch_failed) {
        die $catch_error;
    }
    
    # Return based on context
    return $wantarray ? @ret : $ret[0];
}

1;

__END__

=head1 NAME

Try::Tiny - Minimal try/catch with proper preservation of $@

=head1 SYNOPSIS

    use Try::Tiny;
    
    try {
        die "foo";
    }
    catch {
        warn "caught error: $_";
    }
    finally {
        print "cleanup\n";
    };

=head1 DESCRIPTION

This is a bundled implementation of Try::Tiny for PerlOnJava,
providing compatibility with the CPAN module.

=cut
