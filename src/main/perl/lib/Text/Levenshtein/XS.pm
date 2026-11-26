#
# This file is part of Text-Levenshtein-XS
#
# This software is copyright (c) 2016 by Nick Logan.
#
# This is free software; you can redistribute it and/or modify it under
# the same terms as the Perl 5 programming language system itself.
#
package Text::Levenshtein::XS;

use 5.008;
use strict;
use warnings FATAL => 'all';
require Exporter;

@Text::Levenshtein::XS::ISA       = qw/Exporter/;
@Text::Levenshtein::XS::EXPORT_OK = qw/distance/;
$Text::Levenshtein::XS::VERSION   = 0.503;

eval {
    require XSLoader;
    XSLoader::load(__PACKAGE__, $Text::Levenshtein::XS::VERSION);
    1;
} or do {
    require DynaLoader;
    DynaLoader::bootstrap(__PACKAGE__, $Text::Levenshtein::XS::VERSION);
    sub dl_load_flags {0} # Prevent DynaLoader from complaining and croaking
};



sub distance {
    return Text::Levenshtein::XS::xs_distance( [unpack('U*', shift)], [unpack('U*', shift)], shift || 0);
}



1;

=pod

=encoding UTF-8

=head1 NAME

Text::Levenshtein::XS - Calculate edit distance based on insertion, deletion, and substitution

=head1 VERSION

version 0.503

=head1 SYNOPSIS

    use Text::Levenshtein::XS qw/distance/;

    print distance('Neil','Niel');
    # prints 2

=head1 DESCRIPTION

Returns the number of edits (insert,delete,substitute) required to turn the source string into the target string. XS implementation (requires a C compiler). Works correctly with utf8.

    use Text::Levenshtein::XS qw/distance/;
    use utf8;

    distance('ⓕⓞⓤⓡ','ⓕⓤⓞⓡ'),
    # prints 2

=for Pod::Coverage dl_load_flags xs_distance

=head1 METHODS

=head2 distance

=over 4

=item Arguments: $source_text, $target_text, (optional) $max_distance

=item Return Value: Int $edit_distance || undef (if max_distance is exceeded)

=back

Returns: int that represents the edit distance between the two argument, or undef if $max_distance threshold is exceeded.

Takes the edit distance between a source and target string using XS 2 vector implementation.

    use Text::Levenshtein::XS qw/distance/;
    print distance('Neil','Niel');
    # prints 2

Stops calculations and returns undef if $max_distance is set, non-zero (0 = no limit), and the algorithm has determined the final distance will be greater than $max_distance.

    my $distance = distance('Neil','Niel',1);
    print (defined $distance) ? $distance : "Exceeded max distance";
    # prints "Exceeded max distance"

=head1 NOTES

Drop in replacement for L<Text::LevenshteinXS>

=head1 SEE ALSO

=over 4

=item * L<Text::Levenshtein::Damerau>

=item * L<Text::Levenshtein::Damerau::PP>

=item * L<Text::Levenshtein::Damerau::XS>

=item * L<Text::Fuzzy>

=item * L<Text::Levenshtein::Flexible>

=back

=head1 REPOSITORY

L<https://github.com/ugexe/Text--Levenshtein--XS>

=for HTML <a href="https://travis-ci.org/ugexe/Text--Levenshtein--XS?branch=release"><img src="https://travis-ci.org/ugexe/Text--Levenshtein--XS.svg?branch=release"></a>
    <a href='https://coveralls.io/r/ugexe/Text--Levenshtein--XS?branch=release'><img src='https://coveralls.io/repos/ugexe/Text--Levenshtein--XS/badge.png?branch=release' alt='Coverage Status' /></a>

=head1 BUGS

Please report bugs to:

L<https://github.com/ugexe/Text--Levenshtein--XS/issues>

=head1 AUTHOR

ugexe <ugexe@cpan.org>

=head1 COPYRIGHT AND LICENSE

This software is copyright (c) 2016 by Nick Logan.

This is free software; you can redistribute it and/or modify it under
the same terms as the Perl 5 programming language system itself.

=cut

__END__



# ABSTRACT: Calculate edit distance based on insertion, deletion, and substitution
