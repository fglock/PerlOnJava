use strict;
use warnings;
use Test::More tests => 8;

package MyGlob {

    sub new {
        my ( $class, $glob ) = @_;

        # Ensure we store a real glob ref, not another object
        die "Data must be a glob ref" unless ref $glob eq 'GLOB';
        return bless [ $glob ], $class;
    }

    use overload '*{}' => sub {
        my $self = shift;
        return $self->[0];
    };
}

# Test 1: Object creation with a basic glob
my $obj = MyGlob->new( \*STDOUT );
ok( ref $obj, 'Object created with STDOUT glob' );

# Test 2: Dereference as filehandle for print
{
    open(my $fh, '>', \my $output) or die "Cannot open scalar filehandle: $!";
    my $fh_obj = MyGlob->new( $fh );
    print {*$fh_obj} "Hello, World!";
    close($fh);
    is( $output, "Hello, World!", 'Dereferenced glob used as filehandle for print' );
}

# Test 3: Use with file operations
{
    my $filename = "test_glob_overload_$$.txt";
    open(my $write_fh, '>', $filename) or die "Cannot open $filename: $!";
    my $write_obj = MyGlob->new( $write_fh );
    print {*$write_obj} "Test content";
    close(*$write_obj);
    
    open(my $read_fh, '<', $filename) or die "Cannot open $filename: $!";
    my $read_obj = MyGlob->new( $read_fh );
    my $content = readline(*$read_obj);
    close(*$read_obj);
    
    is( $content, "Test content", 'File operations through dereferenced glob' );
    unlink $filename;
}

# Test 4: Check ref type of dereferenced glob
is( ref(\*$obj), 'GLOB', 'Reference to dereferenced object returns GLOB ref type' );

# Test 5: Use with select (file handle selection)
{
    open(my $temp_fh, '>', \my $temp_output) or die "Cannot open scalar filehandle: $!";
    my $fh_obj = MyGlob->new( $temp_fh );
    
    my $old_fh = select(*$fh_obj);
    print "Selected output";
    select($old_fh);
    close($temp_fh);
    
    is( $temp_output, "Selected output", 'select() works with dereferenced glob' );
}

# Test 6: Test with STDIN/STDOUT/STDERR
{
    my $stderr_obj = MyGlob->new( \*STDERR );
    ok( *$stderr_obj, 'Can dereference STDERR glob object' );
}

## # Test 7: Test with pipe
## {
##     my $pid = open(my $pipe, '-|');
##     if (!defined $pid) {
##         die "Cannot fork: $!";
##     } elsif ($pid == 0) {
##         # Child process
##         print "From child";
##         exit(0);
##     } else {
##         # Parent process
##         my $pipe_obj = MyGlob->new( $pipe );
##         my $line = readline(*$pipe_obj);
##         close(*$pipe_obj);
##         is( $line, "From child", 'Pipe operations work through dereferenced glob' );
##     }
## }

# Test 8: Test fileno
{
    open(my $fh, '>', \my $dummy) or die "Cannot open scalar filehandle: $!";
    my $fh_obj = MyGlob->new( $fh );
    my $fileno = fileno(*$fh_obj);
    ok( defined($fileno), 'fileno() works with dereferenced glob' );
    close($fh);
}
