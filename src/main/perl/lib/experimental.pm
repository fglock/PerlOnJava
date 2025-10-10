package experimental;

use strict;
use warnings;

our $VERSION = '0.032';

# Map of experimental features to their minimum Perl version
my %min_version = (
    args_array_with_signatures => '5.20.0',
    bitwise         => '5.22.0',
    builtin         => '5.35.7',
    current_sub     => '5.16.0',
    declared_refs   => '5.26.0',
    defer           => '5.35.4',
    evalbytes       => '5.16.0',
    extra_paired_delimiters => '5.35.9',
    fc              => '5.16.0',
    isa             => '5.31.7',
    lexical_subs    => '5.18.0',
    postderef       => '5.20.0',
    postderef_qq    => '5.20.0',
    refaliasing     => '5.22.0',
    regex_sets      => '5.18.0',
    signatures      => '5.20.0',
    smartmatch      => '5.10.0',
    switch          => '5.10.0',
    try             => '5.34.0',
    unicode_eval    => '5.16.0',
    unicode_strings => '5.12.0',
);

# Additional features to enable when enabling a feature
my %additional = (
    postderef     => ['postderef_qq'],
    switch        => ['smartmatch'],
    declared_refs => ['refaliasing'],
);

sub _enable {
    my $pragma = shift;
    
    # Enable the feature
    if (exists $min_version{$pragma}) {
        require feature;
        feature->import($pragma);
        
        # Disable the experimental warning
        require warnings;
        warnings->unimport("experimental::$pragma");
        
        # Enable additional features if needed
        if ($additional{$pragma}) {
            _enable(@{ $additional{$pragma} });
        }
    }
    else {
        require Carp;
        Carp::croak("Can't enable unknown feature $pragma");
    }
}

sub _disable {
    my $pragma = shift;
    
    if (exists $min_version{$pragma}) {
        require feature;
        feature->unimport($pragma);
        
        # Re-enable the experimental warning
        require warnings;
        warnings->import("experimental::$pragma");
        
        # Disable additional features if needed
        if ($additional{$pragma}) {
            _disable(@{ $additional{$pragma} });
        }
    }
    else {
        require Carp;
        Carp::croak("Can't disable unknown feature $pragma");
    }
}

sub import {
    my ($self, @pragmas) = @_;
    
    for my $pragma (@pragmas) {
        _enable($pragma);
    }
    return;
}

sub unimport {
    my ($self, @pragmas) = @_;
    
    for my $pragma (@pragmas) {
        _disable($pragma);
    }
    return;
}

1;

__END__

=head1 NAME

experimental - Experimental features made easy

=head1 SYNOPSIS

  use experimental 'declared_refs';
  
  my \$x = \$y;  # No warning

=head1 DESCRIPTION

This module enables experimental features and disables their warnings.

=head1 AVAILABLE FEATURES

The following experimental features are available:

=over 4

=item * declared_refs

=item * refaliasing

=item * signatures

=item * try

=item * And many more...

=back

=head1 SEE ALSO

L<feature>, L<warnings>

=cut
