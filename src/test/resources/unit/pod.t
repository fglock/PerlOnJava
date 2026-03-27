# Test POD (Plain Old Documentation) parsing

use strict;
use warnings;

print "1..6\n";

my $test = 1;

=pod

Basic POD block

=cut

print "ok $test - basic POD block\n";
$test++;

=head1 NAME

Test documentation

=cut

print "ok $test - =head1 POD block\n";
$test++;

=begin scrumbly

This is inside a scrumbly format block.

=end scrumbly

This text is between =end scrumbly and =cut.
Per perlpod, this should be treated as POD, not code.
foo bar baz

=cut

print "ok $test - =begin/=end block with trailing content\n";
$test++;

=begin comment

A comment block

=end comment

More POD content after =end but before =cut

=cut

print "ok $test - =begin comment block\n";
$test++;

=pod

=end

standalone =end stays in POD

=cut

print "ok $test - standalone =end inside POD\n";
$test++;

=begin cut

this format is named 'cut'

=end cut

still in POD after =end cut

=cut

print "ok $test - =begin cut / =end cut format\n";
