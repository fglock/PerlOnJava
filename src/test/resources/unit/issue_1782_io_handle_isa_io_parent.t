use strict;
use warnings;
use Test::More tests => 4;

open my $file, '<', __FILE__ or die "open __FILE__: $!";
my $io = *{$file}{IO};

ok($io, 'lexical filehandle has an IO reference');
ok(UNIVERSAL::isa($io, 'IO::Handle'), 'IO reference is an IO::Handle');
ok(UNIVERSAL::isa($io, 'IO'), 'IO::Handle parent class is recognized');
ok($io->isa('IO'), 'method isa recognizes IO::Handle parent class');

close $file;
